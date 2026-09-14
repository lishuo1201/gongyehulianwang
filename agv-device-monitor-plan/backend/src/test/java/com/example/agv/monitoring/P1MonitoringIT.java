package com.example.agv.monitoring;

import com.example.agv.DemoProperties;
import com.example.agv.collector.ModbusTcpDeviceClient;
import com.example.agv.collector.TestDeviceServer;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class P1MonitoringIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_p1_test").withUsername("u"+UUID.randomUUID().toString().substring(0,8))
            .withPassword(UUID.randomUUID().toString()).withCommand("--default-time-zone=+00:00")
            .withStartupTimeout(Duration.ofSeconds(40)).withReuse(false)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(3306))));

    @Test void staleLifecycleAndRetentionUseRealCommittedData() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var settings=MonitorSettings.from(new MockEnvironment());
        var repository=new MonitorRepository(jdbc,new DataSourceTransactionManager(source),settings);
        var nanos=new AtomicLong(); Instant origin=Instant.parse("2026-09-14T00:00:00Z");
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return origin.plusNanos(nanos.get()); }
        };
        var metrics=new SimpleMeterRegistry();
        try (var wire=new TestDeviceServer()) {
            wire.frozen.set(true); wire.heartbeat.set(1); wire.battery.set(19);
            long id=repository.transaction(() -> repository.create("AGV-P1","test","127.0.0.1",wire.port(),1,true));
            var demo=new DemoProperties("127.0.0.1",wire.port(),List.of("127.0.0.1"),List.of(wire.port()),10);
            try (var service=new MonitorService(repository,new ModbusTcpDeviceClient(settings),settings,demo,clock,nanos::get,false,metrics)) {
                service.start(); collect(service,() -> Objects.equals(1,repository.snapshot(id).get("heartbeat")));
                nanos.set(9_000_000_000L);
                collect(service,() -> origin.plusSeconds(9).equals(repository.snapshot(id).get("lastResponseAt")));
                assertEquals(0,count(jdbc,"alarm WHERE rule_code='DATA_STALE'"));
                nanos.set(10_000_000_000L); service.watch();
                assertEquals("STALE",repository.snapshot(id).get("dataQuality"));
                long first=jdbc.queryForObject("SELECT id FROM alarm WHERE rule_code='DATA_STALE'",Long.class);
                assertEquals("WARNING",service.ack(first).get("severity"));
                nanos.set(11_000_000_000L); wire.invalid.set(true);
                collect(service,() -> "INVALID".equals(repository.snapshot(id).get("dataQuality")));
                assertEquals("ACTIVE",service.ack(first).get("status"));
                nanos.set(21_000_000_000L); service.watch();
                assertEquals("OFFLINE",repository.snapshot(id).get("connectionStatus"));
                assertEquals("ACTIVE",service.ack(first).get("status"));
                wire.invalid.set(false); wire.heartbeat.set(2); nanos.set(22_000_000_000L);
                collect(service,() -> Objects.equals(2,repository.snapshot(id).get("heartbeat")));
                assertEquals("RECOVERED",service.ack(first).get("status"));
                nanos.set(31_000_000_000L);
                collect(service,() -> origin.plusSeconds(31).equals(repository.snapshot(id).get("lastResponseAt")));
                nanos.set(32_000_000_000L); service.watch();
                long second=jdbc.queryForObject("SELECT id FROM alarm WHERE rule_code='DATA_STALE' AND active_slot=1",Long.class);
                assertNotEquals(first,second);
                service.patch(id,null,false);
                assertEquals("SUPPRESSED",service.ack(second).get("status"));
                assertTrue(metrics.get("agv.poll.duration").tag("outcome","VALID").timer().count()>0);
                assertTrue(metrics.get("agv.poll.errors").tag("kind","INVALID").counter().count()>0);
            }
            // Independent timestamps: UTC DATETIME边界，不用受本机时区影响的Timestamp.valueOf。
            Instant cutoff=origin.minus(Duration.ofDays(7));
            var rows=new ArrayList<Object[]>();
            for (int i=0;i<1001;i++) rows.add(new Object[]{UUID.randomUUID().toString(),id,LocalDateTime.ofInstant(cutoff.minusSeconds(1),ZoneOffset.UTC)});
            rows.add(new Object[]{UUID.randomUUID().toString(),id,LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC)});
            jdbc.batchUpdate("INSERT INTO device_sample_history (sample_key,device_id,sampled_at,run_state,battery_percent,speed_mm_s,position_code,target_code,fault_code,heartbeat,created_at) "
                    +"VALUES (?,?,?,'RUNNING',76,1200,3,7,0,1,UTC_TIMESTAMP(3))",rows);
            long alarms=count(jdbc,"alarm"),events=count(jdbc,"device_state_event");
            assertThrows(IllegalArgumentException.class,() -> repository.deleteHistoryBefore(cutoff,1001));
            assertEquals(1000,repository.deleteHistoryBefore(cutoff,1000));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_sample_history WHERE sampled_at < ?",Integer.class,LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC)));
            try (var retention=new HistoryRetention(repository,Clock.fixed(origin,ZoneOffset.UTC),metrics,7)) {
                retention.runOnce();
                assertEquals(1,metrics.get("agv.history.deleted").counter().count());
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_sample_history WHERE sampled_at = ?",Integer.class,LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC)));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_sample_history WHERE sampled_at < ?",Integer.class,LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC)));
                assertEquals(alarms,count(jdbc,"alarm")); assertEquals(events,count(jdbc,"device_state_event"));
                // Failed maintenance is visible: 注入查询失败后计数增加，恢复表后仍可执行下一轮。
                jdbc.execute("RENAME TABLE device_sample_history TO temporarily_missing_history");
                try { retention.runOnce(); assertEquals(1,metrics.get("agv.history.failures").counter().count()); }
                finally { jdbc.execute("RENAME TABLE temporarily_missing_history TO device_sample_history"); }
                retention.runOnce();
            }
        } finally { metrics.close(); }
    }
    private static long count(JdbcTemplate jdbc,String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private static void collect(MonitorService service,BooleanSupplier done) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (!done.getAsBoolean() && System.nanoTime()<deadline) { service.scan(); Thread.sleep(20); }
        assertTrue(done.getAsBoolean());
    }
}
