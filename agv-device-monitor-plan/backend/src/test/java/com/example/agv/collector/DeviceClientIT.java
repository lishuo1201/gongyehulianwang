package com.example.agv.collector;

import com.example.agv.monitoring.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceClientIT {
    private static Device device(int port,int unit) { return new Device(unit,"AGV-00"+unit,"test","127.0.0.1",port,unit,true,0,Instant.now(),Instant.now()); }
    private static MonitorSettings settings() { return MonitorSettings.from(new MockEnvironment()); }
    @Test void decodesRealTcpReusesConnectionAndRejectsInvalidBusinessData() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            var first=client.read(device(server.port(),1));
            assertEquals(DeviceClient.ReadResult.Kind.VALID,first.kind()); assertEquals(1.2,first.sample().speedMps());
            assertEquals(76,client.read(device(server.port(),1)).sample().batteryPercent()); assertEquals(1,server.connections());
            server.invalid.set(true);var invalid=client.read(device(server.port(),1));
            assertTrue(invalid.normalResponse());assertNull(invalid.sample());assertEquals(DeviceClient.ReadResult.Kind.INVALID,invalid.kind());
        }
    }
    @Test void protocolExceptionDoesNotRetryOrClaimNormalResponse() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            server.protocolError.set(true);var result=client.read(device(server.port(),1));
            assertEquals(DeviceClient.ReadResult.Kind.PROTOCOL,result.kind());assertFalse(result.normalResponse());assertEquals(1,server.reads.get());
        }
    }
    @Test void silentDeviceHasDeadlineWhileOtherDevicesContinue() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            server.silent.set(2); ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                long start=System.nanoTime(); var slow=executor.submit(() -> client.read(device(server.port(),2)));
                assertEquals(DeviceClient.ReadResult.Kind.VALID,client.read(device(server.port(),1)).kind());
                assertEquals(DeviceClient.ReadResult.Kind.VALID,client.read(device(server.port(),3)).kind());
                assertEquals(DeviceClient.ReadResult.Kind.COMMUNICATION,slow.get(2300,TimeUnit.MILLISECONDS).kind());
                assertTrue((System.nanoTime()-start)/1_000_000<2200,"Cycle exceeded measured tolerance");
                server.silent.set(0);assertEquals(DeviceClient.ReadResult.Kind.VALID,client.read(device(server.port(),2)).kind());
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void refusesWrongUnitAndClosesLateResponseConnection() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            server.wrongUnit.set(true);assertFalse(client.read(device(server.port(),1)).normalResponse());
            server.wrongUnit.set(false);server.delay.set(1200);
            assertFalse(client.read(device(server.port(),1)).normalResponse());
            server.delay.set(0);assertTrue(client.read(device(server.port(),1)).normalResponse());
        }
    }
    @Test void rejectsWrongProtocolAndMalformedPayloadsWithoutAcceptingMeasurements() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            server.wrongProtocol.set(true);
            assertFalse(client.read(device(server.port(),1)).normalResponse());
            server.wrongProtocol.set(false);
            for (int size:new int[]{2,17,19}) {
                server.payloadSize.set(size);
                assertEquals(DeviceClient.ReadResult.Kind.PROTOCOL,client.read(device(server.port(),1)).kind());
            }
            server.payloadSize.set(18); server.wrongTransaction.set(true);
            assertFalse(client.read(device(server.port(),1)).normalResponse());
            server.wrongTransaction.set(false);
            assertTrue(client.read(device(server.port(),1)).normalResponse());
        }
    }
    @Test void disconnectCancelsRetryAndAllowsANewReadLater() throws Exception {
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            server.silent.set(1);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                var pending=executor.submit(() -> client.read(device(server.port(),1)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while (server.reads.get()==0 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(1,server.reads.get());
                client.disconnect(1);
                assertFalse(pending.get(2,TimeUnit.SECONDS).normalResponse());
                assertEquals(1,server.reads.get(),"cancelled cycle must not reconnect and retry");
                server.silent.set(0);
                assertTrue(client.read(device(server.port(),1)).normalResponse());
                client.close();
                assertFalse(client.read(device(server.port(),1)).normalResponse());
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void refusedConnectionReturnsAndCloseReleasesSocket() throws Exception {
        int port;
        try (var server=new TestDeviceServer();var client=new ModbusTcpDeviceClient(settings())) {
            port=server.port();assertTrue(client.read(device(port,1)).normalResponse());
            client.close();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(server.connections()!=0 && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals(0,server.connections());
        }
        try(var client=new ModbusTcpDeviceClient(settings())) { assertFalse(client.read(device(port,1)).normalResponse()); }
    }
}
