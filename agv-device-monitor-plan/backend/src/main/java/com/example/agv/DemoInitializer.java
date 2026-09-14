package com.example.agv;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seed missing devices：T02，Flyway迁移之后、监控ApplicationReadyEvent之前，补齐三台演示台账。
 * 只有demo profile启用此初始化器；它不会启动Modbus模拟器，也不把默认电量写成真实测量。
 * <p>Database wins：设备已存在时保留名称、启停和连接元组，配置文件改动不能覆盖业务维护结果。
 * 初始deviceCode按Unit ID生成只是方便演示，不意味着后续可以靠编码末尾数字推断实际读取目标。
 * Evidence：BackendApplicationIT验证空库、重启、约束及旧配置保留。
 */
@Component
@Profile("demo")
@DependsOnDatabaseInitialization
@EnableConfigurationProperties(DemoProperties.class)
public class DemoInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoInitializer.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DemoProperties properties;

    public DemoInitializer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                           DemoProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Seed atomically after migration: 迁移后初始化；任何插入冲突都回滚本轮，不能吞异常冒充成功。
        // Single-instance startup: 当前仅承诺单实例初始化，运行期登记上限由后续配置服务负责。
        transactions.executeWithoutResult(status -> {
            for (int unit = 1; unit <= 3; unit++) {
                String code = String.format(Locale.ROOT, "AGV-%03d", unit);
                var existing = jdbc.query("SELECT host, port, unit_id FROM device WHERE device_code = ?",
                        (rs, row) -> new Endpoint(rs.getString("host"), rs.getInt("port"), rs.getInt("unit_id")), code);
                if (!existing.isEmpty()) {
                    Endpoint stored = existing.get(0);
                    if (!stored.host().equals(properties.seedHost()) || stored.port() != properties.seedPort()
                            || stored.unit() != unit) {
                        // Preserve device identity: 仅种子配置有差异时沿用库中连接并明确提示，不覆盖用户配置。
                        log.warn("Demo seed mismatch for {}: stored={}:{}/unit={}, configured={}:{}/unit={}; keeping database values",
                                code, stored.host(), stored.port(), stored.unit(),
                                properties.seedHost(), properties.seedPort(), unit);
                    }
                    // Do not reset on startup：无论连接是否与种子相同，已有台账都直接保留，不能重置enabled/name。
                    continue;
                }
                if (jdbc.queryForObject("SELECT COUNT(*) FROM device", Long.class) >= properties.maxDevices()) {
                    throw new IllegalStateException("Demo initialization would exceed demo.max-devices");
                }
                jdbc.update("""
                        INSERT INTO device (device_code, name, host, port, unit_id, enabled, config_revision,
                                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, TRUE, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                        """, code, code, properties.seedHost(), properties.seedPort(), unit);
                Long id = jdbc.queryForObject("SELECT id FROM device WHERE device_code = ?", Long.class, code);
                // Keep unknown values null: 登记不代表已有测量；快照与台账在同一事务提交。
                jdbc.update("""
                        INSERT INTO device_snapshot
                            (device_id, connection_status, data_quality, run_state, snapshot_revision, updated_at)
                        VALUES (?, 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 0, UTC_TIMESTAMP(3))
                        """, id);
            }
        });
    }

    private record Endpoint(String host, int port, int unit) { }
}
