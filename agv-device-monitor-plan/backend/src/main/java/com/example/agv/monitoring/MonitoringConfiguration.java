package com.example.agv.monitoring;

import com.example.agv.DemoProperties;
import com.example.agv.collector.DeviceClient;
import com.example.agv.collector.ModbusTcpDeviceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * Wire the runtime：T01/T06/T14，复用Spring管理的JdbcTemplate、事务管理器和Micrometer注册表组装监控组件。
 * 正常运行注入UTC墙上时钟与nanoTime；测试可直接构造MonitorService注入可控时间，不必真的等待十秒。
 * monitor.enabled=false关闭整组监控Bean；poll.enabled=false仅关闭服务的自动扫描/刷新调度，供手动驱动测试。
 * 非Web上下文不启动这些网络/后台资源，单元测试不因加载普通Spring上下文就访问设备。
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="monitor.enabled",matchIfMissing=true)
@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class MonitoringConfiguration {
    @Bean MonitorSettings monitorSettings(Environment env) { return MonitorSettings.from(env); }
    @Bean DeviceClient deviceClient(MonitorSettings settings) { return new ModbusTcpDeviceClient(settings); }
    @Bean MonitorRepository monitorRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,MonitorSettings settings) {
        return new MonitorRepository(jdbc,manager,settings);
    }
    @Bean MonitorService monitorService(MonitorRepository repository,DeviceClient client,MonitorSettings settings,Environment env,MeterRegistry metrics) {
        // Share allow-list semantics: API 与种子读取同一组明确允许的目标，不扩大默认访问范围。
        DemoProperties demo=new DemoProperties(env.getProperty("demo.seed-host","simulator"),
                env.getProperty("demo.seed-port",Integer.class,1502),
                List.of(env.getProperty("demo.allowed-hosts","simulator").split(",")),
                java.util.Arrays.stream(env.getProperty("demo.allowed-ports","1502").split(",")).map(Integer::valueOf).toList(),
                env.getProperty("demo.max-devices",Integer.class,10));
        return new MonitorService(repository,client,settings,demo,Clock.systemUTC(),System::nanoTime,
                env.getProperty("poll.enabled",Boolean.class,true),metrics);
    }
    @Bean HistoryRetention historyRetention(MonitorRepository repository,Environment env,MeterRegistry metrics) {
        return new HistoryRetention(repository,Clock.systemUTC(),metrics,
                env.getProperty("history.retention-days",Integer.class,7));
    }
    // Readiness, not vehicle safety：采集服务健康不是设备安全证明；数据库就绪由独立db健康项检查。
    @Bean HealthIndicator collectorHealthIndicator(MonitorService service) {
        return () -> service.healthy() ? Health.up().build() : Health.down().build();
    }
}
