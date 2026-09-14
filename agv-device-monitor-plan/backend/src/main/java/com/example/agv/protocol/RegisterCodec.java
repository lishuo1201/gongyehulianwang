package com.example.agv.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decode the register map：T03，只处理八个寄存器的16字节业务数据，不接触TCP或数据库。
 * <p>Address vs offset：请求起始地址0对应文档标号40001；40001不作为线上的起始地址发送。
 * 每寄存器2字节，块内偏移0/2/4/6/8/10/12/14依次为版本、状态、电量、速度、位置、目标、故障、心跳。
 * 例如速度寄存器04 B0表示1200mm/s，即1.2m/s；同一次读取取得整块，减少跨次读取的时间错位。
 * <p>Keep boundaries separate：MBAP头、Unit ID、功能码与字节数由ModbusTcpDeviceClient校验；
 * 此处收到的是去掉响应功能码和字节数后的payload，不能再按完整Modbus报文偏移解码。
 */
public final class RegisterCodec {
    public static final int START_ADDRESS = 0;
    public static final int REGISTER_COUNT = 8;
    public static final int PAYLOAD_BYTES = REGISTER_COUNT * Short.BYTES;

    private RegisterCodec() {
    }

    public static DeviceSample decode(byte[] payload) {
        // Require a complete block: 长度必须精确匹配，拒绝截断、补零或忽略多余字节。
        if (payload == null || payload.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Expected exactly " + PAYLOAD_BYTES + " payload bytes");
        }
        var buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = readUnsigned(buffer);
        // Reject unknown layouts first: 未知版本不能继续用版本 1 的点表解释后续字段。
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported protocolVersion: " + version);
        }
        return new DeviceSample(version, RunState.fromCode(readUnsigned(buffer)),
                readUnsigned(buffer), readUnsigned(buffer), readUnsigned(buffer),
                readUnsigned(buffer), readUnsigned(buffer), readUnsigned(buffer));
    }

    /**
     * Encode a payload：把合法模型还原为点表字节；不发送功能码06/16，也不获得设备控制能力。
     * 独立模拟器保留自己的编码路径，测试另用固定字节校验，防止两端共享同一错误而互测通过。
     */
    public static byte[] encode(DeviceSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sample must not be null");
        }
        // Encode validated values: 构造器已校验范围；转 short 仅保留合法 uint16 的位模式。
        // Read-only monitoring: 编码数据块本身不发送设备写指令。
        return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) sample.protocolVersion())
                .putShort((short) sample.runState().code())
                .putShort((short) sample.batteryPercent())
                .putShort((short) sample.speedMmS())
                .putShort((short) sample.positionCode())
                .putShort((short) sample.targetCode())
                .putShort((short) sample.faultCode())
                .putShort((short) sample.heartbeat())
                .array();
    }

    private static int readUnsigned(ByteBuffer buffer) {
        // Preserve uint16: FF FF 是 65535，不能把 Java 有符号 short 的 -1 传给业务层。
        return Short.toUnsignedInt(buffer.getShort());
    }
}
