package com.example.agv.monitoring;

import org.springframework.core.env.Environment;

/**
 * One set of defaults：T05/T06/T08/T10，全部时间字段单位为毫秒，权威参数表见docs/01-requirements.md。
 * connectTimeout/requestTimeout约束阶段等待，cycleBudget约束含重试的整轮；attempts=2表示总共两次尝试。
 * offline看正常响应间隔，stale看已接受的有效心跳，historyInterval看成功保存的采样间隔，不能共用一个时钟基准。
 * sseHeartbeat是浏览器链路保活，不用于判断设备心跳。配置加载即校验，避免运行后才发现阈值自相矛盾。
 */
public record MonitorSettings(int interval, int connectTimeout, int requestTimeout, int cycleBudget,
                              int attempts, int retryDelay, int workers, int queueCapacity,
                              int offline, int stale, int watchdog, int historyInterval,
                              int batteryTrigger, int batteryRecover, int sseInterval, int sseHeartbeat) {
    public static MonitorSettings from(Environment env) {
        return new MonitorSettings(value(env,"poll.interval-ms",1000), value(env,"poll.connect-timeout-ms",800),
                value(env,"poll.request-timeout-ms",800), value(env,"poll.cycle-budget-ms",1800),
                value(env,"poll.max-attempts",2), value(env,"poll.retry-delay-ms",100),
                value(env,"poll.workers",4), value(env,"poll.queue-capacity",16),
                value(env,"device.offline-ms",10000), value(env,"device.stale-ms",10000),
                value(env,"device.watchdog-ms",1000), value(env,"history.interval-ms",10000),
                value(env,"alarm.battery-trigger-below",20), value(env,"alarm.battery-recover-at-least",25),
                value(env,"sse.snapshot-ms",1000), value(env,"sse.heartbeat-ms",15000));
    }

    public MonitorSettings {
        for (int n : new int[]{interval,connectTimeout,requestTimeout,cycleBudget,attempts,retryDelay,
                workers,queueCapacity,offline,stale,watchdog,historyInterval,batteryTrigger,batteryRecover,
                sseInterval,sseHeartbeat}) {
            if (n <= 0) throw new IllegalArgumentException("Monitoring settings must be positive");
        }
        if (attempts > 2 || cycleBudget >= offline || batteryRecover <= batteryTrigger || batteryRecover > 100)
            throw new IllegalArgumentException("Invalid retry budget or battery thresholds");
    }

    private static int value(Environment env, String key, int fallback) {
        return env.getProperty(key, Integer.class, fallback);
    }
}
