package com.example.agv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class BackendApplicationIT {

    // Own the database lifecycle: 固定镜像、随机凭据和端口，只清理此测试创建的容器，不接受外部 JDBC 地址。
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_t02_test")
            .withUsername("u" + UUID.randomUUID().toString().substring(0, 8))
            .withPassword(UUID.randomUUID().toString())
            .withCommand("--default-time-zone=+00:00")
            .withStartupTimeout(Duration.ofSeconds(40))
            .withReuse(false)
            .withLabel("com.example.agv.test", "t02")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(3306))));

    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void startApplication() {
        application = start("demo", 1502);
    }

    @AfterAll
    static void closeApplication() {
        if (application != null) {
            application.close();
        }
    }

    private static ConfigurableApplicationContext start(String profile, int seedPort) {
        // Override ambient settings: 测试连接参数使用应用参数最高优先级，防止误连个人数据库。
        return new SpringApplicationBuilder(BackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=" + profile,
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.flyway.url=" + MYSQL.getJdbcUrl(),
                "--spring.flyway.user=" + MYSQL.getUsername(),
                "--spring.flyway.password=" + MYSQL.getPassword(),
                "--demo.seed-host=simulator", "--demo.seed-port=" + seedPort,
                "--demo.allowed-hosts=simulator", "--demo.allowed-ports=1502,1503", "--demo.max-devices=10");
    }

    private static JdbcTemplate jdbc() {
        return application.getBean(JdbcTemplate.class);
    }

    private static long device(String code) {
        return jdbc().queryForObject("SELECT id FROM device WHERE device_code = ?", Long.class, code);
    }

    private static void rolledBack(Runnable action) {
        // Isolate fixture writes: 约束测试的修改统一回滚，避免不同测试互相污染。
        new TransactionTemplate(application.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            try {
                action.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void servesRealHttpWithDatabaseHealth() throws Exception {
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", application.getBean(JsonMapper.class).readTree(response.body()).path("status").asString());
    }

    @Test
    void migratesAndPreservesUserConfigurationAcrossRealRestart(CapturedOutput output) {
        assertEquals(3L, jdbc().queryForObject("SELECT COUNT(*) FROM device", Long.class));
        assertEquals(3L, jdbc().queryForObject("SELECT COUNT(*) FROM device_snapshot", Long.class));
        assertEquals(5L, jdbc().queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('device','device_snapshot','device_sample_history','device_state_event','alarm')
                """, Long.class));
        var initial = jdbc().queryForMap("SELECT * FROM device_snapshot WHERE device_id = ?", device("AGV-001"));
        assertEquals("UNKNOWN", initial.get("connection_status"));
        assertEquals("UNKNOWN", initial.get("data_quality"));
        assertNull(initial.get("battery_percent"));
        assertNull(initial.get("last_fresh_at"));
        jdbc().update("UPDATE device SET name=?, enabled=FALSE, config_revision=7 WHERE device_code=?",
                "二号补料车", "AGV-002");
        jdbc().update("UPDATE device_snapshot SET connection_status='DISABLED' WHERE device_id=?", device("AGV-002"));
        List<Map<String, Object>> before = jdbc().queryForList("SELECT * FROM device ORDER BY device_code");
        List<Map<String, Object>> snapshots = jdbc().queryForList("SELECT * FROM device_snapshot ORDER BY device_id");
        application.close();
        application = start("demo", 1503);
        assertEquals(before, jdbc().queryForList("SELECT * FROM device ORDER BY device_code"));
        assertEquals(snapshots, jdbc().queryForList("SELECT * FROM device_snapshot ORDER BY device_id"));
        assertTrue(output.getOut().contains("Demo seed mismatch for AGV-002"));
        assertTrue(output.getOut().contains("keeping database values"));
        assertEquals(0, application.getBean(Flyway.class).migrate().migrationsExecuted);
        assertEquals(1L, jdbc().queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Long.class));
        assertEquals("+00:00", jdbc().queryForObject("SELECT @@session.time_zone", String.class));
        assertNotEquals("root", MYSQL.getUsername());
    }

    @Test
    void doesNotActivateDemoInitializerOutsideDemoProfile() {
        try (var nonDemo = start("integration", 1502)) {
            assertTrue(nonDemo.getBeansOfType(DemoInitializer.class).isEmpty());
        }
    }

    @Test
    void rejectsDuplicateIdentityAndOrphanSnapshots() {
        rolledBack(() -> {
            assertThrows(DuplicateKeyException.class, () -> insertDevice("AGV-001", 99));
            assertThrows(DuplicateKeyException.class, () -> insertDevice("ANOTHER-CODE", 1));
            assertSqlError(3819, () -> insertDevice("lowercase", 99));
            assertSqlError(1452, () -> jdbc().update("""
                    INSERT INTO device_snapshot (device_id, connection_status, data_quality, run_state, updated_at)
                    VALUES (-1, 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', UTC_TIMESTAMP(3))
                    """));
            assertSqlError(1451,
                    () -> jdbc().update("DELETE FROM device WHERE device_code='AGV-001'"));
        });
    }

    @Test
    void enforcesMeasurementRangesAndSampleIdentity() {
        rolledBack(() -> {
            long id = device("AGV-001");
            jdbc().update("UPDATE device_snapshot SET battery_percent=0, heartbeat=65535, fault_code=65535 WHERE device_id=?", id);
            assertEquals(65535, jdbc().queryForObject("SELECT heartbeat FROM device_snapshot WHERE device_id=?", Integer.class, id));
            // Fixed SQL only: 测试非法字段，列名不来自外部输入。
            for (String sql : List.of(
                    "UPDATE device_snapshot SET battery_percent=101 WHERE device_id=?",
                    "UPDATE device_snapshot SET battery_percent=-1 WHERE device_id=?",
                    "UPDATE device_snapshot SET heartbeat=65536 WHERE device_id=?",
                    "UPDATE device_snapshot SET speed_mm_s=-1 WHERE device_id=?",
                    "UPDATE device_snapshot SET speed_mm_s=3001 WHERE device_id=?",
                    "UPDATE device_snapshot SET target_code=10000 WHERE device_id=?",
                    "UPDATE device_snapshot SET data_quality='good' WHERE device_id=?",
                    "UPDATE device SET unit_id=0 WHERE id=?",
                    "UPDATE device SET unit_id=248 WHERE id=?",
                    "UPDATE device SET port=65536 WHERE id=?",
                    "UPDATE device SET enabled=2 WHERE id=?",
                    "UPDATE device SET config_revision=-1 WHERE id=?")) {
                assertSqlError(3819, () -> jdbc().update(sql, id));
            }
            String sampleKey = UUID.randomUUID().toString();
            insertSample(id, sampleKey, 100);
            assertThrows(DuplicateKeyException.class, () -> insertSample(id, sampleKey, 100));
            assertSqlError(3819, () -> insertSample(id, UUID.randomUUID().toString(), 101));
            assertSqlError(3819, () -> jdbc().update("""
                    UPDATE device_sample_history SET run_state='FAULT' WHERE sample_key=?
                    """, sampleKey));
        });
    }

    @Test
    void retainsPastAlarmsWhileEnforcingExactlyOneActiveSlot() {
        rolledBack(() -> {
            long id = device("AGV-001");
            for (int episode = 0; episode < 2; episode++) {
                insertAlarm(id, "LOW_BATTERY", "WARNING");
                jdbc().update("""
                        UPDATE alarm SET status='RECOVERED', active_slot=NULL, recovered_at=UTC_TIMESTAMP(3),
                            closed_at=UTC_TIMESTAMP(3), close_reason='RULE_RECOVERED'
                        WHERE device_id=? AND rule_code='LOW_BATTERY' AND status='ACTIVE'
                        """, id);
            }
            insertAlarm(id, "LOW_BATTERY", "WARNING");
            assertThrows(DuplicateKeyException.class, () -> insertAlarm(id, "LOW_BATTERY", "WARNING"));
            insertAlarm(id, "DEVICE_OFFLINE", "CRITICAL");
            assertEquals(2L, jdbc().queryForObject("SELECT COUNT(*) FROM alarm WHERE device_id=? AND status='RECOVERED'", Long.class, id));
            assertEquals(2L, jdbc().queryForObject("SELECT COUNT(*) FROM alarm WHERE device_id=? AND status='ACTIVE'", Long.class, id));
            for (String sql : List.of(
                    "UPDATE alarm SET active_slot=NULL WHERE device_id=? AND status='ACTIVE'",
                    "UPDATE alarm SET active_slot=0 WHERE device_id=? AND status='ACTIVE'",
                    "UPDATE alarm SET active_slot=0 WHERE device_id=? AND status='RECOVERED'",
                    "UPDATE alarm SET closed_at=UTC_TIMESTAMP(3) WHERE device_id=? AND status='ACTIVE'",
                    "UPDATE alarm SET close_reason=NULL WHERE device_id=? AND status='RECOVERED'")) {
                assertSqlError(3819, () -> jdbc().update(sql, id));
            }
            jdbc().update("""
                    UPDATE alarm SET status='SUPPRESSED', active_slot=NULL, closed_at=UTC_TIMESTAMP(3),
                        close_reason='DEVICE_DISABLED' WHERE device_id=? AND status='ACTIVE'
                    """, id);
            assertEquals(2L, jdbc().queryForObject("SELECT COUNT(*) FROM alarm WHERE device_id=? AND status='SUPPRESSED' AND recovered_at IS NULL", Long.class, id));
        });
    }

    @Test
    void rollsBackAllSeedWritesWhenAnotherCodeOwnsTheEndpoint() {
        // Destructive fixtures stay inside this disposable database: 仅重排本测试容器内的演示数据。
        long second = device("AGV-002");
        long third = device("AGV-003");
        jdbc().update("DELETE FROM device_snapshot WHERE device_id IN (?, ?)", second, third);
        jdbc().update("DELETE FROM device WHERE id IN (?, ?)", second, third);
        var initializer = new DemoInitializer(jdbc(), application.getBean(PlatformTransactionManager.class),
                new DemoProperties("simulator", 1502, List.of("simulator"), List.of(1502), 10));
        try {
            var limited = new DemoInitializer(jdbc(), application.getBean(PlatformTransactionManager.class),
                    new DemoProperties("simulator", 1502, List.of("simulator"), List.of(1502), 2));
            assertThrows(IllegalStateException.class, () -> limited.run(new DefaultApplicationArguments()));
            assertEquals(0L, jdbc().queryForObject("SELECT COUNT(*) FROM device WHERE device_code='AGV-002'", Long.class));
            assertEquals(1L, jdbc().queryForObject("SELECT COUNT(*) FROM device_snapshot", Long.class));
            insertDevice("EXISTING-OWNER", 3);
            assertThrows(DuplicateKeyException.class, () -> initializer.run(new DefaultApplicationArguments()));
            assertEquals(0L, jdbc().queryForObject("SELECT COUNT(*) FROM device WHERE device_code='AGV-002'", Long.class));
            assertEquals(1L, jdbc().queryForObject("SELECT COUNT(*) FROM device_snapshot", Long.class));
            assertEquals(1L, jdbc().queryForObject("SELECT COUNT(*) FROM device WHERE device_code='EXISTING-OWNER'", Long.class));
        } finally {
            jdbc().update("DELETE FROM device WHERE device_code='EXISTING-OWNER'");
            initializer.run(new DefaultApplicationArguments());
        }
    }

    @Test
    void concurrentInsertsLeaveOneActiveAlarmAndLoserCanReread() throws Exception {
        long id = device("AGV-003");
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        DataSource source = application.getBean(DataSource.class);
        try {
            var first = pool.submit(() -> raceToInsert(source, id, barrier));
            var second = pool.submit(() -> raceToInsert(source, id, barrier));
            assertEquals(1, first.get(8, TimeUnit.SECONDS) + second.get(8, TimeUnit.SECONDS));
            assertEquals(1L, jdbc().queryForObject("SELECT COUNT(*) FROM alarm WHERE device_id=? AND rule_code='DEVICE_FAULT'", Long.class, id));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            jdbc().update("DELETE FROM alarm WHERE device_id=? AND rule_code='DEVICE_FAULT'", id);
        }
    }

    private static int raceToInsert(DataSource source, long id, CyclicBarrier barrier) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM alarm WHERE device_id=? AND rule_code='DEVICE_FAULT' AND active_slot=1")) {
                query.setLong(1, id);
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
            barrier.await(5, TimeUnit.SECONDS);
            try (var insert = connection.prepareStatement(ALARM_INSERT)) {
                insert.setLong(1, id);
                insert.setString(2, "DEVICE_FAULT");
                insert.setString(3, "CRITICAL");
                insert.executeUpdate();
                connection.commit();
                return 1;
            } catch (SQLException conflict) {
                // Re-read after rollback: 验证真实唯一冲突后的新事务可见性；其他错误必须原样失败。
                connection.rollback();
                assertEquals(1062, conflict.getErrorCode());
                assertTrue(conflict.getMessage().contains("uk_alarm_active"));
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM alarm WHERE device_id=? AND rule_code='DEVICE_FAULT' AND active_slot=1")) {
                    query.setLong(1, id);
                    try (var result = query.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(1, result.getInt(1));
                    }
                }
                connection.commit();
                return 0;
            }
        }
    }

    private static void assertSqlError(int code, Runnable action) {
        // Verify the server error, not a guessed Spring category: MySQL CHECK 3819 使用 HY000，可能映射为未分类异常。
        var exception = assertThrows(DataAccessException.class, action::run);
        var sql = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(code, sql.getErrorCode(), sql.getMessage());
    }

    private static void insertDevice(String code, int unit) {
        jdbc().update("""
                INSERT INTO device (device_code,name,host,port,unit_id,created_at,updated_at)
                VALUES (?, ?, 'simulator', 1502, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, code, code, unit);
    }

    private static void insertSample(long deviceId, String key, int battery) {
        jdbc().update("""
                INSERT INTO device_sample_history (sample_key,device_id,sampled_at,run_state,battery_percent,
                    speed_mm_s,position_code,target_code,fault_code,heartbeat,created_at)
                VALUES (?, ?, UTC_TIMESTAMP(3), 'RUNNING', ?, 1200, 0, 9999, 0, 65535, UTC_TIMESTAMP(3))
                """, key, deviceId, battery);
    }

    private static final String ALARM_INSERT = """
            INSERT INTO alarm (device_id,rule_code,severity,status,active_slot,trigger_value,`last_value`,
                triggered_at,last_observed_at)
            VALUES (?, ?, ?, 'ACTIVE', 1, '19', '19', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """;

    private static void insertAlarm(long id, String rule, String severity) {
        jdbc().update(ALARM_INSERT, id, rule, severity);
    }
}
