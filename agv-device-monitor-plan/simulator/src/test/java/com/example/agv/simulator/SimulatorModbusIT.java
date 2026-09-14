package com.example.agv.simulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.agv.simulator.SimulatedFleet.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulatorModbusIT {
    private SimulatedFleet fleet;
    private SimulatorModbusServer server;

    @BeforeEach
    void startServerOnEphemeralLoopbackPort() throws Exception {
        fleet = new SimulatedFleet();
        for (int id = 1; id <= 3; id++) {
            fleet.changeScenario(id, FROZEN);
        }
        server = new SimulatorModbusServer(fleet, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void stopServerAndItsConnections() throws Exception {
        server.close();
    }

    @Test
    void readsThreeUnitsAndPartialRangesWithIndependentWireExpectations() throws Exception {
        // Freeze before reading: 固定样例不受每秒调度影响，预期字节独立于服务端编码器。
        try (var client = new WireClient(server.port())) {
            assertArrayEquals(hex("03 10 00 01 00 01 00 4C 04 B0 00 03 00 07 00 00 00 00"),
                    client.exchange(1, hex("03 00 00 00 08")));
            assertArrayEquals(new int[] {1, 2, 23, 0, 9001, 9001, 0, 0}, client.read(2));
            assertArrayEquals(new int[] {1, 0, 88, 0, 5, 5, 0, 0}, client.read(3));
            assertArrayEquals(hex("03 02 00 4C"), client.exchange(1, hex("03 00 02 00 01")));
            assertArrayEquals(hex("03 04 00 00 00 00"), client.exchange(1, hex("03 00 06 00 02")));
        }
    }

    @Test
    void keepsSameConnectionUsableWhenAnotherUnitIsSilentOrUnknown() throws Exception {
        fleet.changeScenario(2, SILENT);
        try (var client = new WireClient(server.port())) {
            client.send(2, hex("03 00 00 00 08"));
            assertEquals(76, client.read(1)[2]);
            assertEquals(88, client.read(3)[2]);
            client.assertNoResponse();
            client.send(4, hex("03 00 00 00 08"));
            client.assertNoResponse();
            assertEquals(76, client.read(1)[2]);
            fleet.changeScenario(2, NORMAL);
            assertEquals(23, client.read(2)[2]);
        }
    }

    @Test
    void rejectsUnsupportedFunctionsRangesAndMalformedRequestsWithoutWriting() throws Exception {
        fleet.changeScenario(1, FROZEN);
        try (var client = new WireClient(server.port())) {
            int[] before = client.read(1);
            for (int function : new int[] {1, 2, 4, 5, 6, 15, 16, 22, 23, 127}) {
                byte[] request = hex("06 00 02 00 00");
                request[0] = (byte) function;
                assertArrayEquals(new byte[] {(byte) (function | 0x80), 1}, client.exchange(1, request));
            }
            for (String request : new String[] {"03 00 06 00 03", "03 00 08 00 01", "03 9C 41 00 08"}) {
                assertArrayEquals(hex("83 02"), client.exchange(1, hex(request)));
            }
            for (String request : new String[] {"03 00 00 00 00", "03 00 00 00 7E", "03 00", "03 00 00 00 01 00"}) {
                assertArrayEquals(hex("83 03"), client.exchange(1, hex(request)));
            }
            assertArrayEquals(before, client.read(1));
        }
    }

    @Test
    void exposesAllRespondingScenariosAndPreservesValidFrozenHistoryOverTcp() throws Exception {
        try (var client = new WireClient(server.port())) {
            fleet.changeScenario(1, LOW_BATTERY);
            assertEquals(19, client.read(1)[2]);
            fleet.changeScenario(1, NORMAL);
            assertEquals(76, client.read(1)[2]);
            fleet.changeScenario(1, FAULT);
            int[] fault = fleet.changeScenario(1, FROZEN).registers();
            assertArrayEquals(fault, client.read(1));
            fleet.changeScenario(1, INVALID);
            assertEquals(101, client.read(1)[2]);
            fleet.changeScenario(1, FROZEN);
            fleet.tick();
            assertArrayEquals(fault, client.read(1));
            fleet.changeScenario(1, NORMAL);
            int[] normal = client.read(1);
            assertEquals(1, normal[1]);
            assertEquals(1200, normal[3]);
            assertEquals(0, normal[6]);
        }
    }

    @Test
    void preservesUnsignedHeartbeatOverTcp() throws Exception {
        server.close();
        fleet = new SimulatedFleet();
        // Prepare before starting the timer: 不与每秒调度竞争，独立构造65535心跳的真实线缆样例。
        for (int i = 0; i < 65535; i++) {
            fleet.tick();
        }
        fleet.changeScenario(3, FROZEN);
        server = new SimulatorModbusServer(fleet, "127.0.0.1", 0);
        server.start();
        try (var client = new WireClient(server.port())) {
            assertEquals(65535, client.read(3)[7]);
        }
    }

    @Test
    void scheduledUpdatesAdvanceOtherUnitsWhileFrozenBlockStaysIdentical() throws Exception {
        fleet.changeScenario(1, FROZEN);
        fleet.changeScenario(2, NORMAL);
        try (var client = new WireClient(server.port())) {
            int[] frozen = client.read(1);
            int initial = client.read(2)[7];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            int current;
            do {
                Thread.sleep(25);
                current = client.read(2)[7];
            } while (current == initial && System.nanoTime() < deadline);
            assertNotEquals(initial, current);
            assertArrayEquals(frozen, client.read(1));
        }
    }

    @Test
    void concurrentScenarioSwitchesProduceOnlyCoherentTcpSamples() throws Exception {
        var writer = Executors.newSingleThreadExecutor();
        var reading = new AtomicBoolean(true);
        try (var client = new WireClient(server.port())) {
            var switching = writer.submit(() -> {
                // Keep writing throughout network reads: 避免写线程提前结束，使测试退化为顺序读取。
                while (reading.get()) {
                    fleet.changeScenario(1, FAULT);
                    fleet.changeScenario(1, NORMAL);
                }
            });
            for (int i = 0; i < 100; i++) {
                int[] raw = client.read(1);
                if (raw[1] == 3) {
                    assertEquals(0, raw[3]);
                    assertEquals(2, raw[6]);
                } else {
                    assertEquals(1, raw[1]);
                    assertEquals(1200, raw[3]);
                    assertEquals(0, raw[6]);
                }
            }
            reading.set(false);
            switching.get(5, TimeUnit.SECONDS);
        } finally {
            reading.set(false);
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failsOnOccupiedPortWithoutStoppingTheExistingServer() throws Exception {
        try (var conflicting = new SimulatorModbusServer(fleet, "127.0.0.1", server.port())) {
            Exception failure = assertThrows(Exception.class, conflicting::start);
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertInstanceOf(java.net.BindException.class, cause);
        }
        try (var client = new WireClient(server.port())) {
            assertEquals(76, client.read(1)[2]);
        }
    }

    @Test
    void rejectsAnEmptyPduWithoutBreakingOtherConnections() throws Exception {
        try (var malformed = new WireClient(server.port())) {
            malformed.send(1, new byte[0]);
            assertEquals(-1, malformed.input.read());
        }
        try (var healthy = new WireClient(server.port())) {
            assertEquals(23, healthy.read(2)[2]);
        }
    }

    @Test
    void closesActiveConnectionsAndReleasesTheListeningPort() throws Exception {
        assertEquals("UP", server.health().getStatus().getCode());
        int port = server.port();
        try (var client = new WireClient(port)) {
            client.read(1);
            server.close();
            assertEquals("DOWN", server.health().getStatus().getCode());
            assertEquals(-1, client.input.read());
            try (var probe = new Socket()) {
                assertThrows(IOException.class, () -> probe.connect(new InetSocketAddress("127.0.0.1", port), 500));
            }
        }
    }

    static byte[] hex(String value) {
        return HexFormat.of().parseHex(value.replace(" ", ""));
    }

    /** Independent TCP probe: 使用JDK socket直接检查MBAP、事务号及PDU，不复用被测编码器。 */
    static final class WireClient implements AutoCloseable {
        private final Socket socket = new Socket();
        final DataInputStream input;
        private final DataOutputStream output;
        private int transaction;

        WireClient(int port) throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(1500);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        int send(int unit, byte[] pdu) throws IOException {
            int id = ++transaction;
            output.writeShort(id);
            output.writeShort(0);
            output.writeShort(pdu.length + 1);
            output.writeByte(unit);
            output.write(pdu);
            output.flush();
            return id;
        }

        byte[] exchange(int unit, byte[] pdu) throws IOException {
            int id = send(unit, pdu);
            assertEquals(id, input.readUnsignedShort(), "transactionId");
            assertEquals(0, input.readUnsignedShort(), "protocolId");
            int length = input.readUnsignedShort();
            assertTrue(length >= 2 && length <= 254, "MBAP length");
            assertEquals(unit, input.readUnsignedByte(), "unitId");
            byte[] response = new byte[length - 1];
            input.readFully(response);
            return response;
        }

        int[] read(int unit) throws IOException {
            byte[] pdu = exchange(unit, hex("03 00 00 00 08"));
            assertEquals(18, pdu.length);
            assertEquals(3, pdu[0]);
            assertEquals(16, pdu[1]);
            var payload = ByteBuffer.wrap(pdu, 2, 16);
            int[] result = new int[8];
            for (int i = 0; i < 8; i++) {
                result[i] = Short.toUnsignedInt(payload.getShort());
            }
            return result;
        }

        void assertNoResponse() throws IOException {
            socket.setSoTimeout(200);
            try {
                assertThrows(SocketTimeoutException.class, () -> input.readUnsignedByte());
            } finally {
                socket.setSoTimeout(1500);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
