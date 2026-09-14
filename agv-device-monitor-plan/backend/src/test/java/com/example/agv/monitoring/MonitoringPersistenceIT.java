package com.example.agv.monitoring;

import com.example.agv.DemoProperties;
import com.example.agv.collector.ModbusTcpDeviceClient;
import com.example.agv.collector.TestDeviceServer;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DuplicateKeyException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MonitoringPersistenceIT {
    // Isolated failure drill: 只在自建临时 MySQL 中注入写入失败，绝不读取外部数据库配置。
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_monitor_test")
            .withUsername("u"+UUID.randomUUID().toString().substring(0,8)).withPassword(UUID.randomUUID().toString())
            .withCommand("--default-time-zone=+00:00","--log-bin-trust-function-creators=1")
            .withStartupTimeout(Duration.ofSeconds(40)).withReuse(false)
            .withLabel("com.example.agv.test","monitor-persistence")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(3306))));

    @Test void failedSampleRollsBackWithoutLosingCommunicationOrRetryBaseline() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var settings=MonitorSettings.from(new MockEnvironment());
        var repository=new MonitorRepository(jdbc,new DataSourceTransactionManager(source),settings);
        var time=new AtomicLong();
        Instant origin=Instant.parse("2026-09-14T00:00:00Z");
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return origin.plusNanos(time.get()); }
        };
        try (var server=new TestDeviceServer()) {
            server.frozen.set(true); server.heartbeat.set(1);
            var demo=new DemoProperties("127.0.0.1",server.port(),List.of("127.0.0.1"),List.of(server.port()),10);
            long id=repository.transaction(() -> repository.create("AGV-01","一号车","127.0.0.1",server.port(),1,true));
            try (var service=new MonitorService(repository,new ModbusTcpDeviceClient(settings),settings,demo,clock,time::get,false)) {
                service.start();
                collectUntil(service,() -> Objects.equals(1,repository.snapshot(id).get("heartbeat")));
                assertEquals(1,count(jdbc,"device_sample_history"));
                long events=count(jdbc,"device_state_event");
                service.refreshView();
                Object committed=service.view().get("devices");
                // Fail late in transaction: 在历史与事件已执行后拒绝告警，验证四表一起回滚。
                jdbc.execute("CREATE TRIGGER reject_test_alarm BEFORE INSERT ON alarm FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test alarm failure'");
                time.set(11_000_000_000L); server.heartbeat.set(8); server.battery.set(19);
                collectUntil(service,() -> !service.healthy());
                assertEquals(1,repository.snapshot(id).get("heartbeat"));
                assertEquals(76,repository.snapshot(id).get("batteryPercent"));
                assertEquals(1,count(jdbc,"device_sample_history"));
                assertEquals(events,count(jdbc,"device_state_event"));
                assertEquals(0,count(jdbc,"alarm"));
                service.refreshView();
                assertEquals(committed,service.view().get("devices"));
                assertEquals("DEGRADED",service.view().get("collectorStatus"));
                jdbc.execute("DROP TRIGGER reject_test_alarm");

                // Preserve only communication: 健康扫描可保存响应时间，但不得补写失败样本及其告警。
                time.set(12_000_000_000L); service.watch();
                Map<String,Object> watched=repository.snapshot(id);
                assertEquals("ONLINE",watched.get("connectionStatus"));
                assertEquals("STALE",watched.get("dataQuality"));
                assertEquals(origin.plusSeconds(11),watched.get("lastResponseAt"));
                assertEquals(1,watched.get("heartbeat"));
                assertEquals(1,count(jdbc,"device_sample_history"));
                assertEquals(0,count(jdbc,"alarm"));

                // Retry same heartbeat: 失败样本未推进基准，再收到心跳8必须完整接受。
                collectUntil(service,() -> Objects.equals(8,repository.snapshot(id).get("heartbeat")));
                assertEquals(2,count(jdbc,"device_sample_history"));
                assertEquals(1,count(jdbc,"alarm"));
                assertEquals("GOOD",repository.snapshot(id).get("dataQuality"));
                long alarm=jdbc.queryForObject("SELECT id FROM alarm",Long.class);
                Object firstAck=service.ack(alarm).get("acknowledgedAt");
                assertEquals(firstAck,service.ack(alarm).get("acknowledgedAt"));
                assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM alarm WHERE id=?",String.class,alarm));
                service.refreshView();
                assertEquals(1,((List<?>)service.view().get("activeAlarms")).size());
                assertEquals("RUNNING",service.view().get("collectorStatus"));

                Map<String,Object> saved=repository.snapshot(id);
                service.patch(id,null,false);
                assertEquals(saved.get("lastFreshAt"),repository.snapshot(id).get("lastFreshAt"));
                assertEquals(saved.get("lastResponseAt"),repository.snapshot(id).get("lastResponseAt"));
                assertEquals("SUPPRESSED",jdbc.queryForObject("SELECT status FROM alarm WHERE id=?",String.class,alarm));
                Object revision=repository.deviceView(id).get("configRevision");
                service.patch(id,null,false);
                assertEquals(revision,repository.deviceView(id).get("configRevision"));
                service.patch(id,"新别名",true);
                assertEquals(1,repository.device(id).unitId());
            }
            try (var restarted=new MonitorService(repository,new ModbusTcpDeviceClient(settings),settings,demo,clock,time::get,false)) {
                restarted.start();
                assertEquals("UNKNOWN",repository.snapshot(id).get("connectionStatus"));
                assertEquals("STALE",repository.snapshot(id).get("dataQuality"));
                assertEquals(origin.plusSeconds(12),repository.snapshot(id).get("lastFreshAt"));
                collectUntil(restarted,() -> "GOOD".equals(repository.snapshot(id).get("dataQuality")));
                assertEquals(3,count(jdbc,"device_sample_history"));
                assertEquals(2,count(jdbc,"alarm"));
                String key=jdbc.queryForObject("SELECT sample_key FROM device_sample_history ORDER BY id DESC LIMIT 1",String.class);
                Object revision=repository.snapshot(id).get("snapshotRevision");
                DeviceState repeated=repository.initialState(repository.device(id),time.get());
                repeated.response(repeated.sample,time.get(),clock.instant());
                repository.persist(repository.device(id),repeated,true,true,key,clock.instant(),"REDELIVERY");
                assertEquals(revision,repository.snapshot(id).get("snapshotRevision"));
                assertEquals(3,count(jdbc,"device_sample_history"));

                // Retry outside transaction: 首次冲突前的改名必须先回滚，第二次才能重读数据库。
                AtomicInteger attempts=new AtomicInteger();
                repository.transaction(() -> {
                    assertEquals("新别名",repository.device(id).name());
                    if (attempts.incrementAndGet()==1) {
                        repository.patch(id,"must roll back",true);
                        jdbc.update("INSERT INTO alarm (device_id,rule_code,severity,status,active_slot,trigger_value,`last_value`,triggered_at,last_observed_at) "
                                + "VALUES (?,'LOW_BATTERY','WARNING','ACTIVE',1,'19','19',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id);
                    }
                    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE device_id=? AND active_slot=1",Integer.class,id));
                    return null;
                });
                assertEquals(2,attempts.get());
                attempts.set(0);
                assertThrows(DuplicateKeyException.class,() -> repository.transaction(() -> {
                    attempts.incrementAndGet();
                    return repository.create("AGV-01","duplicate","127.0.0.1",server.port(),2,true);
                }));
                assertEquals(1,attempts.get(),"known device conflict must not be retried as an alarm race");

                // Check persisted sampling boundaries: 以重启后的第12秒为起点，验证0/1/9/10秒真实落库。
                long historyBefore=count(jdbc,"device_sample_history");
                for (int second : new int[]{13,21,22}) {
                    time.set(second*1_000_000_000L);
                    int heartbeat=server.heartbeat.incrementAndGet();
                    collectUntil(restarted,() -> Objects.equals(heartbeat,repository.snapshot(id).get("heartbeat")));
                    assertEquals(historyBefore+(second==22 ? 1 : 0),count(jdbc,"device_sample_history"));
                }
                long activeId=jdbc.queryForObject("SELECT id FROM alarm WHERE active_slot=1",Long.class);
                Map<String,Object> active=restarted.ack(activeId);
                // Observe without reopening: 100次新有效观测维持同一活动告警，确认不释放活动槽。
                for (int i=0;i<100;i++) {
                    time.set(23_000_000_000L+i*1_000_000L);
                    int heartbeat=server.heartbeat.incrementAndGet();
                    collectUntil(restarted,() -> Objects.equals(heartbeat,repository.snapshot(id).get("heartbeat")));
                }
                assertEquals(2,count(jdbc,"alarm"));
                assertEquals(activeId,jdbc.queryForObject("SELECT id FROM alarm WHERE active_slot=1",Long.class));
                assertEquals(active.get("acknowledgedAt"),restarted.ack(activeId).get("acknowledgedAt"));
                assertEquals(historyBefore+1,count(jdbc,"device_sample_history"));
                assertEquals(clock.instant(),restarted.ack(activeId).get("lastObservedAt"));

                // Persist one offline transition: 精确9.999/10秒边界，不重复事件，也不补陈旧历史。
                long connectionEvents=jdbc.queryForObject("SELECT COUNT(*) FROM device_state_event WHERE event_type='CONNECTION'",Long.class);
                time.addAndGet(9_999_000_000L); restarted.watch();
                assertEquals("ONLINE",repository.snapshot(id).get("connectionStatus"));
                time.addAndGet(1_000_000L); restarted.watch(); restarted.watch();
                assertEquals("OFFLINE",repository.snapshot(id).get("connectionStatus"));
                assertEquals(connectionEvents+1,jdbc.queryForObject("SELECT COUNT(*) FROM device_state_event WHERE event_type='CONNECTION'",Long.class));
                assertEquals(historyBefore+1,count(jdbc,"device_sample_history"));
                assertEquals("ACTIVE",restarted.ack(activeId).get("status"));

                // Exercise real fault lifecycle: 故障999连续100次保持同一告警，恢复后再发生使用新ID。
                server.fault.set(999);
                for (int i=0;i<100;i++) {
                    time.addAndGet(1_000_000L);
                    int heartbeat=server.heartbeat.incrementAndGet();
                    collectUntil(restarted,() -> Objects.equals(heartbeat,repository.snapshot(id).get("heartbeat")));
                }
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE rule_code='DEVICE_FAULT'",Integer.class));
                long faultId=jdbc.queryForObject("SELECT id FROM alarm WHERE rule_code='DEVICE_FAULT'",Long.class);
                Object acknowledged=restarted.ack(faultId).get("acknowledgedAt");
                int stillFault=server.heartbeat.incrementAndGet();
                collectUntil(restarted,() -> Objects.equals(stillFault,repository.snapshot(id).get("heartbeat")));
                assertEquals("ACTIVE",restarted.ack(faultId).get("status"));
                assertEquals(acknowledged,restarted.ack(faultId).get("acknowledgedAt"));
                server.fault.set(0);
                int recovered=server.heartbeat.incrementAndGet();
                collectUntil(restarted,() -> Objects.equals(recovered,repository.snapshot(id).get("heartbeat")));
                assertEquals("RECOVERED",restarted.ack(faultId).get("status"));
                server.fault.set(999);
                int reopened=server.heartbeat.incrementAndGet();
                collectUntil(restarted,() -> Objects.equals(reopened,repository.snapshot(id).get("heartbeat")));
                assertNotEquals(faultId,jdbc.queryForObject("SELECT id FROM alarm WHERE rule_code='DEVICE_FAULT' AND active_slot=1",Long.class));
            }
        }
    }

    private static long count(JdbcTemplate jdbc,String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);
    }
    private static void collectUntil(MonitorService service,BooleanSupplier done) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while (!done.getAsBoolean() && System.nanoTime()<deadline) { service.scan(); Thread.sleep(20); }
        assertTrue(done.getAsBoolean(),"collection did not reach expected committed state");
    }
}
