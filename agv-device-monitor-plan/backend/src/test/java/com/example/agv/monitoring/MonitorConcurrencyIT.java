package com.example.agv.monitoring;

import com.example.agv.DemoProperties;
import com.example.agv.collector.DeviceClient;
import com.example.agv.collector.ModbusTcpDeviceClient;
import com.example.agv.collector.TestDeviceServer;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MonitorConcurrencyIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_concurrency_test")
            .withUsername("u"+UUID.randomUUID().toString().substring(0,8)).withPassword(UUID.randomUUID().toString())
            .withCommand("--default-time-zone=+00:00").withStartupTimeout(Duration.ofSeconds(40)).withReuse(false)
            .withLabel("com.example.agv.test","monitor-concurrency")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(3306))));

    @Test void queuedAndLateResultsRespectDisableAndRestartKeepsActiveAlarm(CapturedOutput output) throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        // Force a small queue: 一工作线程、一排队槽使拒绝和排队停用窗口可控，不等待偶然竞争。
        var settings=MonitorSettings.from(new MockEnvironment().withProperty("poll.workers","1").withProperty("poll.queue-capacity","1"));
        var repository=new MonitorRepository(jdbc,new DataSourceTransactionManager(source),settings);
        try (var server=new TestDeviceServer()) {
            server.frozen.set(true); server.heartbeat.set(1);
            var demo=new DemoProperties("127.0.0.1",server.port(),List.of("127.0.0.1"),List.of(server.port()),10);
            long first=repository.transaction(() -> repository.create("AGV-01","first","127.0.0.1",server.port(),1,true));
            long second=repository.transaction(() -> repository.create("AGV-02","second","127.0.0.1",server.port(),2,false));
            long third=repository.transaction(() -> repository.create("AGV-03","third","127.0.0.1",server.port(),3,false));
            var client=new GatedClient(settings);
            try (var service=new MonitorService(repository,client,settings,demo,Clock.systemUTC(),System::nanoTime,false)) {
                service.start();
                Pending initial=next(service,client);
                assertEquals(first,initial.deviceId());
                for (int i=0;i<20;i++) service.scan();
                assertEquals(1,server.reads.get(),"in-flight device must not start overlapping reads");
                service.patch(second,null,true); service.patch(third,null,true);
                service.scan();
                assertTrue(service.skipped()>0,"one of two tasks must be rejected when the only queue slot is full");
                service.patch(second,null,false); service.patch(third,null,false);
                initial.release().countDown();
                until(() -> Objects.equals(1,repository.snapshot(first).get("heartbeat")));

                // Hold a real response: 网络已返回，但人为延迟交付；启停后仍须拒绝旧revision结果。
                server.heartbeat.set(8);
                Pending late=next(service,client);
                assertEquals(first,late.deviceId());
                assertEquals(2,server.reads.get(),"disabled queued tasks must never reach the TCP client");
                service.patch(first,null,false);
                Object revision=repository.deviceView(first).get("configRevision");
                service.patch(first,null,false);
                assertEquals(revision,repository.deviceView(first).get("configRevision"));
                service.patch(first,null,true);
                late.release().countDown();
                server.heartbeat.set(9); server.battery.set(19);
                Pending fresh=next(service,client);
                assertEquals("UNKNOWN",repository.snapshot(first).get("connectionStatus"));
                assertEquals(1,repository.snapshot(first).get("heartbeat"),"late heartbeat8 must not overwrite retained value");
                fresh.release().countDown();
                until(() -> Objects.equals(9,repository.snapshot(first).get("heartbeat")));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE active_slot=1",Integer.class));

                // Release rejected markers: 曾排队或被拒绝的设备重新启用后都必须能够再次被采集。
                service.patch(first,null,false);
                for (long id : new long[]{second,third}) {
                    service.patch(id,null,true);
                    Pending enabled=next(service,client);
                    assertEquals(id,enabled.deviceId());
                    enabled.release().countDown();
                    until(() -> Objects.equals(9,repository.snapshot(id).get("heartbeat")));
                    service.patch(id,null,false);
                }
                service.patch(first,null,true);
                Pending restored=next(service,client); restored.release().countDown();
                until(() -> "GOOD".equals(repository.snapshot(first).get("dataQuality")));
                service.refreshView();
                var before=service.view();
                // Fail the second query: 已读快照后告警查询失败，仍只能保留完整旧视图。
                jdbc.execute("RENAME TABLE alarm TO unavailable_test_alarm");
                try {
                    service.refreshView();
                    var failed=service.view();
                    assertEquals(before.get("devices"),failed.get("devices"));
                    assertEquals(before.get("activeAlarms"),failed.get("activeAlarms"));
                    assertEquals("DEGRADED",failed.get("collectorStatus"));
                } finally { jdbc.execute("RENAME TABLE unavailable_test_alarm TO alarm"); }
                service.refreshView();
                assertEquals("RUNNING",service.view().get("collectorStatus"));
            }
            long alarm=jdbc.queryForObject("SELECT id FROM alarm WHERE device_id=? AND active_slot=1",Long.class,first);
            var restartedClient=new GatedClient(settings);
            try (var service=new MonitorService(repository,restartedClient,settings,demo,Clock.systemUTC(),System::nanoTime,false)) {
                service.start();
                assertEquals("UNKNOWN",repository.snapshot(first).get("connectionStatus"));
                assertEquals(alarm,jdbc.queryForObject("SELECT id FROM alarm WHERE device_id=? AND active_slot=1",Long.class,first));
                Pending sample=next(service,restartedClient); sample.release().countDown();
                until(() -> "GOOD".equals(repository.snapshot(first).get("dataQuality")));
                assertEquals(alarm,jdbc.queryForObject("SELECT id FROM alarm WHERE device_id=? AND active_slot=1",Long.class,first));
            }
        }
        assertFalse(output.getAll().contains("event executor terminated"),"disconnect callbacks must finish before Netty shutdown");
        assertFalse(output.getAll().contains("Modbus disconnect drain failed"));
        assertFalse(output.getAll().contains("executor did not terminate"));
    }

    private static Pending next(MonitorService service,GatedClient client) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (System.nanoTime()<deadline) {
            service.scan();
            Pending pending=client.responses.poll(20,TimeUnit.MILLISECONDS);
            if (pending!=null) return pending;
        }
        throw new AssertionError("Expected a real TCP response");
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Expected committed state");
    }
    private record Pending(long deviceId,CountDownLatch release) {}
    private static final class GatedClient implements DeviceClient {
        final ModbusTcpDeviceClient delegate;
        final LinkedBlockingQueue<Pending> responses=new LinkedBlockingQueue<>();
        GatedClient(MonitorSettings settings) { delegate=new ModbusTcpDeviceClient(settings); }
        public ReadResult read(Device device) throws InterruptedException {
            ReadResult result=delegate.read(device);
            var pending=new Pending(device.id(),new CountDownLatch(1));
            responses.add(pending);
            // Gate delivery only: 使用真实TCP结果，不伪造成功；锁外延迟用于确定性验证旧结果隔离。
            if (!pending.release().await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test response was not released");
            return result;
        }
        public void disconnect(long id) { delegate.disconnect(id); }
        public void close() { delegate.close(); }
    }
}
