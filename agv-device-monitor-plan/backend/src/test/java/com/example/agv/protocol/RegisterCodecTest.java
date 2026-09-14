package com.example.agv.protocol;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterCodecTest {
    // Independent fixture: 来自协议文档的固定字节，不调用被测编码器来生成解码输入。
    private static final String FIXTURE = "00 01 00 01 00 4C 04 B0 00 03 00 07 00 00 FF FF";

    @Test
    void decodesTheDocumentedBytesWithoutLosingUnitsOrUnsignedValues() {
        var sample = RegisterCodec.decode(bytes(FIXTURE));
        assertEquals(1, sample.protocolVersion());
        assertEquals(RunState.RUNNING, sample.runState());
        assertEquals(76, sample.batteryPercent());
        assertEquals(1200, sample.speedMmS());
        assertEquals(1.2, sample.speedMps(), 0.000001);
        assertEquals(3, sample.positionCode());
        assertEquals(7, sample.targetCode());
        assertEquals(0, sample.faultCode());
        assertEquals(65535, sample.heartbeat());
    }

    @Test
    void encodesToTheIndependentDocumentedBytes() {
        var sample = new DeviceSample(1, RunState.RUNNING, 76, 1200, 3, 7, 0, 65535);
        assertArrayEquals(bytes(FIXTURE), RegisterCodec.encode(sample));
    }

    @Test
    void preservesZeroFractionalSpeedAndUpperBoundaries() {
        var idle = RegisterCodec.decode(bytes("00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00"));
        assertEquals(new DeviceSample(1, RunState.IDLE, 0, 0, 0, 0, 0, 0), idle);
        var running = RegisterCodec.decode(bytes("00 01 00 01 00 64 0B B8 27 0F 27 0F 00 00 00 00"));
        assertEquals(new DeviceSample(1, RunState.RUNNING, 100, 3000, 9999, 9999, 0, 0), running);
        assertEquals(3.0, running.speedMps(), 0.000001);
        assertEquals(0.5, RegisterCodec.decode(withRegister(3, 500)).speedMps(), 0.000001);
        assertEquals(0.0, RegisterCodec.decode(withRegister(3, 0)).speedMps(), 0.000001);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, IDLE", "1, 0, RUNNING", "2, 0, CHARGING", "3, 1, FAULT",
            "3, 999, FAULT", "3, 65535, FAULT"})
    void acceptsConsistentWireStatesAndPreservesUnknownFaults(int state, int fault, RunState expected) {
        byte[] payload = bytes("00 01 00 00 00 13 00 00 00 00 00 00 00 00 00 00");
        putRegister(payload, 1, state);
        putRegister(payload, 6, fault);
        var sample = RegisterCodec.decode(payload);
        assertEquals(expected, sample.runState());
        assertEquals(state, sample.runState().code());
        assertEquals(19, sample.batteryPercent());
        assertEquals(fault, sample.faultCode());
        assertArrayEquals(payload, RegisterCodec.encode(sample));
    }

    @Test
    void acceptsFirstHeartbeatAndWraparoundButRejectsDuplicatesAsNewObservations() {
        var zero = RegisterCodec.decode(withRegister(7, 0));
        assertTrue(zero.hasNewHeartbeat(null));
        assertTrue(zero.hasNewHeartbeat(65535));
        assertFalse(zero.hasNewHeartbeat(0));
        assertTrue(zero.hasNewHeartbeat(100));
        assertTrue(RegisterCodec.decode(withRegister(7, 101)).hasNewHeartbeat(100));
        assertThrows(IllegalArgumentException.class, () -> zero.hasNewHeartbeat(-1));
        assertThrows(IllegalArgumentException.class, () -> zero.hasNewHeartbeat(65536));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 14, 15, 17, 18})
    void rejectsIncompleteAndOversizedPayloads(int size) {
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.decode(new byte[size]));
    }

    @Test
    void rejectsNullInputRatherThanReturningAnEmptySample() {
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.encode(null));
    }

    @ParameterizedTest
    @CsvSource({"0, 0, protocolVersion", "0, 2, protocolVersion", "0, 65535, protocolVersion",
            "1, 4, runState", "1, 65535, runState", "2, 101, batteryPercent",
            "2, 65535, batteryPercent", "3, 3001, speedMmS", "3, 65535, speedMmS",
            "4, 10000, positionCode", "5, 10000, targetCode"})
    void rejectsUnknownVersionsStatesAndOutOfRangeMeasurements(int offset, int value, String field) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> RegisterCodec.decode(withRegister(offset, value)));
        assertTrue(error.getMessage().contains(field), error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 1", "1, 0, 2", "2, 0, 2", "3, 0, 0",
            "0, 1, 0", "2, 1, 0", "3, 1, 2"})
    void rejectsCrossFieldConflicts(int state, int speed, int fault) {
        byte[] payload = bytes(FIXTURE);
        putRegister(payload, 1, state);
        putRegister(payload, 3, speed);
        putRegister(payload, 6, fault);
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.decode(payload));
    }

    @ParameterizedTest
    @CsvSource({"2, -1", "2, 101", "3, -1", "3, 3001", "4, -1", "4, 10000",
            "5, -1", "5, 10000", "6, -1", "6, 65536", "7, -1", "7, 65536"})
    void preventsDirectConstructionFromBypassingNumericValidation(int offset, int value) {
        int[] raw = {1, 1, 76, 1200, 3, 7, 0, 65535};
        raw[offset] = value;
        assertThrows(IllegalArgumentException.class, () -> new DeviceSample(raw[0], RunState.RUNNING,
                raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]));
    }

    @Test
    void rejectsUnsupportedVersionBeforeInterpretingTheState() {
        byte[] payload = withRegister(0, 2);
        putRegister(payload, 1, 65535);
        var error = assertThrows(IllegalArgumentException.class, () -> RegisterCodec.decode(payload));
        assertTrue(error.getMessage().contains("protocolVersion"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceSample(2, RunState.RUNNING, 76, 1200, 3, 7, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceSample(1, null, 76, 0, 3, 7, 0, 0));
    }

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex.replace(" ", ""));
    }

    private static byte[] withRegister(int offset, int value) {
        byte[] payload = bytes(FIXTURE);
        putRegister(payload, offset, value);
        return payload;
    }

    private static void putRegister(byte[] payload, int offset, int value) {
        // Change one wire value: 直接修改固定样例的高低字节，不经过被测编码器。
        payload[offset * 2] = (byte) (value >>> 8);
        payload[offset * 2 + 1] = (byte) value;
    }
}
