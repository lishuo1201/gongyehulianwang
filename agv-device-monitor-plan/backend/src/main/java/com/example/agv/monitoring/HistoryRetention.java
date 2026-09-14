package com.example.agv.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retain sampled history：T15，只清理device_sample_history，不自动删除告警、事件、快照或设备。
 * 默认保留7天，就绪后1秒执行首次清理，此后每轮结束再等24小时；实际保留窗口约7～8天，失败可更长。
 * 每轮截止时间固定、每批独立提交，避免大事务长期持有删除行锁；无需为本地单实例增加分布式任务平台。
 * Evidence：P1MonitoringIT验证1000行上限、截止边界、其他表保留及失败后再次执行。
 */
public final class HistoryRetention implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(HistoryRetention.class);
    private final MonitorRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final int days;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"agv-history-retention"));
    private final AtomicBoolean started=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();

    public HistoryRetention(MonitorRepository repository,Clock clock,MeterRegistry metrics,int days) {
        if (days<1) throw new IllegalArgumentException("History retention must be at least one day");
        this.repository=repository; this.clock=clock; this.metrics=metrics; this.days=days;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (started.compareAndSet(false,true)) executor.scheduleWithFixedDelay(this::runOnce,1,86400,TimeUnit.SECONDS);
    }
    /**
     * Clean against one cutoff：一轮内不反复读取当前时间移动截止点，便于解释本次到底清理了哪个窗口。
     * 同步入口防止同实例手动调用与调度重叠；它不提供跨实例排他保证。
     */
    public synchronized void runOnce() {
        if (closed.get()) return;
        Instant cutoff=clock.instant().minus(Duration.ofDays(days));
        try {
            int deleted;
            // Continue only when full：不足一批表示当前截止条件下已无更多旧行；整批删满则再检查下一批。
            // Count empty success：空批同样计入batches，deleted只累计实际删除行数。
            do {
                deleted=repository.deleteHistoryBefore(cutoff,1000);
                metrics.counter("agv.history.deleted").increment(deleted);
                metrics.counter("agv.history.batches").increment();
            } while (deleted==1000 && !closed.get() && !Thread.currentThread().isInterrupted());
            log.info("History retention completed before {}",cutoff);
        } catch (RuntimeException failure) {
            // Keep future runs alive: 当前批失败回滚并记录，下次定时运行仍可继续；不吞掉错误冒充成功。
            metrics.counter("agv.history.failures").increment();
            log.error("History retention failed before {}",cutoff,failure);
        }
    }
    @PreDestroy @Override public void close() {
        closed.set(true); executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5,TimeUnit.SECONDS)) log.error("History retention executor did not terminate");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
