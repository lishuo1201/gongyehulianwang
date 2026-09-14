package com.example.agv.collector;

import com.example.agv.monitoring.Device;
import com.example.agv.protocol.DeviceSample;

/**
 * Device access boundary：T05，把Modbus库的类型限制在适配器内部，监控业务只接收读取结果。
 * read执行真实网络读取；disconnect只取消指定deviceId的连接，close释放本客户端全部资源。
 * Read-only scope：这里没有写寄存器或下发任务的方法，模拟监控不能被当成车辆控制服务。
 */
public interface DeviceClient extends AutoCloseable {
    ReadResult read(Device device) throws InterruptedException;
    void disconnect(long deviceId);
    void close();

    /**
     * Separate failure layers：VALID为结构及业务值合法；INVALID为正常协议响应中的非法业务值。
     * PROTOCOL为请求/响应契约错误（含Modbus异常响应）；COMMUNICATION为连接失败或超时等。
     * VALID仍须继续判断心跳新鲜度和数据库提交，不能直接等价于已接受的新业务样本。
     * normalResponse仅决定能否刷新通信证据：INVALID可以，PROTOCOL/COMMUNICATION不可以。
     * reason用于诊断及事件来源，不能用一条错误文本同时代替连接、质量、运行三种状态。
     */
    record ReadResult(Kind kind, DeviceSample sample, String reason) {
        public enum Kind { VALID, INVALID, PROTOCOL, COMMUNICATION }
        public boolean normalResponse() { return kind == Kind.VALID || kind == Kind.INVALID; }
    }
}
