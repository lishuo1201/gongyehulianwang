package com.example.agv.protocol;

/**
 * Wire states：T03，设备上报的业务运行状态，与通信连接、数据质量是三个不同维度。
 * ONLINE设备仍可上报FAULT；OFFLINE时页面仍可保留最后可信的RUNNING，但必须同时提示数据陈旧。
 * UNKNOWN表示监控端还不知道，属于快照模型，不是本协议允许的设备上报值。
 */
public enum RunState {
    IDLE(0), RUNNING(1), CHARGING(2), FAULT(3);

    private final int code;

    RunState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static RunState fromCode(int code) {
        // Use explicit codes: 按协议映射，不依赖 enum 声明顺序，不将未知值降级为空闲。
        return switch (code) {
            case 0 -> IDLE;
            case 1 -> RUNNING;
            case 2 -> CHARGING;
            case 3 -> FAULT;
            default -> throw new IllegalArgumentException("Unknown runState: " + code);
        };
    }
}
