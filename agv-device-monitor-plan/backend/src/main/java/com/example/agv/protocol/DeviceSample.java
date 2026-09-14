package com.example.agv.protocol;

/**
 * Valid measurements：T03，点表定义的一组合法业务数值，字段顺序见 docs/10-modbus-register-map.md。
 * <p>Read in layers：RegisterCodec解码 → 本构造器校验 → DeviceState判断新观测 → MonitorService提交。
 * 构造成功只说明数值与字段组合合法：19%电量、非零故障码也可以合法，同时应产生业务告警。
 * 这里不知道接收时间、上次心跳或数据库结果，不能据此直接宣布ONLINE、GOOD或持久化成功。
 * <p>Keep wire units：速度保留mm/s整数，位置和目标是站点编码，不是地图坐标；输出页面时再换算单位。
 * Evidence：RegisterCodecTest包含独立固定字节样例，不能只用本编码器生成数据来证明本解码器正确。
 */
public record DeviceSample(int protocolVersion, RunState runState, int batteryPercent,
                           int speedMmS, int positionCode, int targetCode, int faultCode, int heartbeat) {

    public DeviceSample {
        // Validate at construction: 解码和直接构造共用校验，非法数据不能成为可信样本。
        if (protocolVersion != 1) {
            throw new IllegalArgumentException("Unsupported protocolVersion: " + protocolVersion);
        }
        if (runState == null) {
            throw new IllegalArgumentException("runState must not be null");
        }
        checkRange("batteryPercent", batteryPercent, 100);
        checkRange("speedMmS", speedMmS, 3000);
        checkRange("positionCode", positionCode, 9999);
        checkRange("targetCode", targetCode, 9999);
        checkRange("faultCode", faultCode, 65535);
        checkRange("heartbeat", heartbeat, 65535);
        // Check related fields: 非零故障必须对应 FAULT；未知但合法的故障码保留原值。
        if ((faultCode != 0) != (runState == RunState.FAULT)) {
            throw new IllegalArgumentException("faultCode and runState disagree");
        }
        // Allow waiting in a task：RUNNING且速度0可以是执行任务时等待；不能反推为空闲。
        // Validate the reverse case：本模拟协议要求非RUNNING状态速度必须为0，这不是所有厂商的通用标准。
        if (runState != RunState.RUNNING && speedMmS != 0) {
            throw new IllegalArgumentException("speedMmS must be zero unless runState is RUNNING");
        }
    }

    public double speedMps() {
        // Keep fractional speed: 转换为 m/s 时避免整数除法截断，内部仍保留原始 mm/s。
        return speedMmS / 1000.0;
    }

    /**
     * Compare heartbeat values：比较上一次已接受样本，不比较大小；65535→0及设备重置均可能发生。
     * null是尚未建立基准，0是合法计数值。相等只能说明本次没有观察到推进，不能独立判断已陈旧10秒。
     * Sampling limit：两次读取之间若恰好绕回同值，本协议没有更多信息用于还原中间过程。
     */
    public boolean hasNewHeartbeat(Integer previousHeartbeat) {
        // Compare valid observations: null 表示首次；回绕或重置后的不同值也算推进。
        // Keep timing outside: 此处不维护历史或判断 10 秒 STALE，由后续采集状态机负责。
        if (previousHeartbeat == null) {
            return true;
        }
        checkRange("previousHeartbeat", previousHeartbeat, 65535);
        return heartbeat != previousHeartbeat;
    }

    private static void checkRange(String field, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(field + " must be between 0 and " + max + ": " + value);
        }
    }
}
