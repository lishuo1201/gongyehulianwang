package com.example.agv.monitoring;

import com.example.agv.protocol.DeviceSample;
import java.time.Instant;

/**
 * Observation state：T06/T07，本进程对一台设备的观察基准与状态候选；由MonitorService持设备锁调用。
 * <p>Three dimensions：connection回答能否正常通信；quality回答数值是否合法、新鲜；
 * sample.runState回答设备最后可信的业务状态。通信中断不等于车辆故障，也不应把电量改成0。
 * <p>Two clocks：Nanos来自单调时钟，用于计算持续时间；Instant用于数据库和页面的UTC时间。
 * 进程重启后不能从旧Instant恢复nanoTime基准，必须重新观察；null计时基准不能用0替代。
 * <p>Commit boundary：response/watch只修改候选对象；只有MonitorService.persist成功后才替换正式基准。
 * Evidence：DeviceStateTest验证阈值与重启，MonitoringPersistenceIT验证提交失败后的状态保留。
 */
public final class DeviceState {
    public String connection = "UNKNOWN";
    public String quality = "UNKNOWN";
    public DeviceSample sample;
    // Accepted baseline：与sample内可供展示的旧心跳区分；重启可保留旧sample但将比较基准置为null。
    public Integer heartbeat;
    public boolean repeatedHeartbeat;
    public Instant lastResponseAt;
    public Instant lastFreshAt;
    // Start a fresh observation window：新登记或重新启用后，从这里计算尚无响应时的离线等待期。
    public long enabledNanos;
    public Long responseNanos;
    public Long freshNanos;
    // Persisted sampling time：只在历史成功提交后推进，失败不能占用下一次保存机会。
    public Long historyNanos;

    public DeviceState(long now, boolean enabled, DeviceSample oldSample) {
        enabledNanos = now;
        sample = oldSample;
        quality = oldSample == null ? "UNKNOWN" : "STALE";
        if (!enabled) connection = "DISABLED";
    }

    /**
     * Copy before changing：建立可丢弃的候选，避免数据库回滚后内存已经接受了新值。
     * DeviceSample与Instant不可变，可安全共享引用；本对象的状态、心跳及计时字段分别复制。
     */
    public DeviceState copy() {
        DeviceState next = new DeviceState(enabledNanos, !connection.equals("DISABLED"), sample);
        next.connection = connection;
        next.quality = quality;
        next.heartbeat = heartbeat;
        next.repeatedHeartbeat = repeatedHeartbeat;
        next.lastResponseAt = lastResponseAt;
        next.lastFreshAt = lastFreshAt;
        next.responseNanos = responseNanos;
        next.freshNanos = freshNanos;
        next.historyNanos = historyNanos;
        return next;
    }

    public DeviceState restart(long now, boolean enabled) {
        // Keep display times: 重建本进程计时，保留旧可信值的展示时间。
        DeviceState next = new DeviceState(now, enabled, sample);
        next.lastResponseAt = lastResponseAt;
        next.lastFreshAt = lastFreshAt;
        return next;
    }

    /**
     * Handle a normal response：仅用于匹配本请求的Modbus正常响应；协议异常或超时不会进入这里。
     * next为null表示响应正常但业务数据非法，仍刷新通信时间，保留最后可信样本和心跳基准。
     * 返回true表示候选接受了新合法观测，允许后续计算业务告警及采样；不代表数据库已经提交。
     */
    public boolean response(DeviceSample next, long now, Instant at) {
        connection = "ONLINE";
        responseNanos = now;
        lastResponseAt = at;
        repeatedHeartbeat = next != null && !next.hasNewHeartbeat(heartbeat);
        if (next == null) {
            // Keep valid history: 正常协议响应中的非法业务值不能污染心跳基准和可信数值。
            quality = "INVALID";
            return false;
        }
        // Do not invent a new observation：即使电量发生变化，心跳未推进也不能替换可信整组样本。
        // Keep prior quality：短时重复不立即STALE；此前为INVALID时也不能仅凭重复心跳改回GOOD。
        if (!next.hasNewHeartbeat(heartbeat)) return false;
        sample = next;
        heartbeat = next.heartbeat();
        freshNanos = now;
        lastFreshAt = at;
        quality = "GOOD";
        return true;
    }

    /**
     * Check elapsed time：独立健康扫描和每次读取结束后都可调用，不依赖等待失败的网络请求完成。
     * 默认最后正常响应后9.999秒不离线，达到10秒离线；正常响应包括业务值非法的响应。
     * 无正常响应优先判断OFFLINE；持续收到重复心跳则可ONLINE + STALE，两种场景不能混为一谈。
     */
    public void watch(long now, MonitorSettings settings) {
        if (connection.equals("DISABLED")) return;
        if (now - (responseNanos == null ? enabledNanos : responseNanos) >= settings.offline() * 1_000_000L) {
            connection = "OFFLINE";
            quality = sample == null ? "UNKNOWN" : "STALE";
        } else if (!quality.equals("INVALID") && freshNanos != null
                && now - freshNanos >= settings.stale() * 1_000_000L) {
            quality = "STALE";
        }
    }

    /**
     * Sample by committed intervals：首次可保存，此后默认至少间隔10秒；调用方还必须保证fresh。
     * 这不是每个自然整十秒补一条记录，暂停、无效或失联期间的缺口不能自动补零。
     */
    public boolean historyDue(long now, MonitorSettings settings) {
        return historyNanos == null || now - historyNanos >= settings.historyInterval() * 1_000_000L;
    }
}
