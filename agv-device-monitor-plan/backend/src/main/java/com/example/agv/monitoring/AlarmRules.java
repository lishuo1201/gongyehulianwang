package com.example.agv.monitoring;

/**
 * Alarm lifecycle：T08/T18，纯规则函数只决定动作，MonitorRepository负责在同一事务中落实。
 * OPEN新建一轮异常，OBSERVE维护持续异常，RECOVER表示规则得到恢复证据，SUPPRESS表示停止监测。
 * NONE既不更新观察时间，也不更改已有告警；“没有新证据”和“异常恢复”含义完全不同。
 * <p>Acknowledgment is separate：确认时间不参与判断，值班人员看过消息不能解除设备异常。
 * Evidence：AlarmRulesTest覆盖规则分支，P1MonitoringIT验证DATA_STALE落库、恢复和再次产生新ID。
 */
public final class AlarmRules {
    private AlarmRules() { }
    public enum Action { OPEN, OBSERVE, RECOVER, SUPPRESS, NONE }

    /**
     * Decide from one candidate：active来自数据库当前活动槽；fresh表示候选中新接受了合法心跳。
     * 本次事务若失败，动作与快照一同回滚，不能把这里的判断当作已对外发布的事实。
     */
    public static Action decide(String rule,DeviceState state,boolean fresh,boolean active,MonitorSettings settings) {
        if (state.connection.equals("DISABLED")) return active ? Action.SUPPRESS : Action.NONE;
        boolean trigger,recover;
        switch (rule) {
            case "DEVICE_OFFLINE" -> {
                // Communication recovery：只需新的正常协议响应，哪怕电量非法，也能证明通信恢复。
                // Business alarms remain separate：同一次响应不能据此恢复低电量或设备故障告警。
                trigger=state.connection.equals("OFFLINE");
                recover=state.connection.equals("ONLINE");
                if (!trigger && !recover) return Action.NONE;
            }
            case "DATA_STALE" -> {
                // Separate storage failure：新心跳未能写库不等于设备冻结，需通信侧的重复心跳事实。
                // Require observed staleness: 重启的旧值、离线或INVALID不能冒充在线心跳冻结。
                trigger=state.connection.equals("ONLINE") && state.quality.equals("STALE") && state.freshNanos!=null && state.repeatedHeartbeat;
                recover=fresh && state.quality.equals("GOOD");
                if (!trigger && !recover) return Action.NONE;
            }
            case "LOW_BATTERY", "DEVICE_FAULT" -> {
                // Trust new samples only: 超时、重复心跳及非法值不能代替业务异常恢复。
                if (!fresh || !state.quality.equals("GOOD") || state.sample==null) return Action.NONE;
                if (rule.equals("LOW_BATTERY")) {
                    // Hysteresis：默认19触发、20～24维持、25恢复，避免在20附近抖动时反复开关告警。
                    // Existing episode only：没有活动告警时，首次读到24不应补造一次低电量异常。
                    trigger=state.sample.batteryPercent()<settings.batteryTrigger();
                    recover=state.sample.batteryPercent()>=settings.batteryRecover();
                } else {
                    trigger=state.sample.faultCode()!=0;
                    recover=!trigger;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported alarm rule: "+rule);
        }
        // Reopen as a new episode：恢复后活动槽已释放，再次满足触发条件时由持久化层生成新记录。
        if (!active) return trigger ? Action.OPEN : Action.NONE;
        if (recover) return Action.RECOVER;
        return Action.OBSERVE;
    }
}
