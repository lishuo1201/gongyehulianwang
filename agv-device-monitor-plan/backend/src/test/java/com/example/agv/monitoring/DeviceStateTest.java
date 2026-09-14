package com.example.agv.monitoring;

import com.example.agv.protocol.DeviceSample;
import com.example.agv.protocol.RunState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DeviceStateTest {
    private final MonitorSettings settings=MonitorSettings.from(new MockEnvironment());
    private final Instant at=Instant.parse("2026-09-14T00:00:00Z");
    private static DeviceSample sample(int heartbeat) { return new DeviceSample(1,RunState.RUNNING,19,1200,3,7,0,heartbeat); }
    @Test void separatesFirstHeartbeatFromUnknownAndWraps() {
        var state=new DeviceState(0,true,null);
        assertNull(state.heartbeat); assertNull(state.sample);
        assertTrue(state.response(sample(65535),0,at));
        assertTrue(state.response(sample(0),1,at.plusMillis(1)));
        assertFalse(state.response(sample(0),2,at.plusMillis(2)));
        assertEquals(at.plusMillis(1),state.lastFreshAt);
    }
    @Test void invalidDoesNotAdvanceValidBaseline() {
        var state=new DeviceState(0,true,null);
        assertFalse(state.response(null,0,at)); assertNull(state.heartbeat);
        assertEquals("ONLINE",state.connection); assertEquals("INVALID",state.quality);
        assertTrue(state.response(sample(8),1,at));
        state.response(null,2,at);
        assertEquals(8,state.heartbeat); assertEquals(19,state.sample.batteryPercent());
        assertFalse(state.response(sample(8),3,at)); assertEquals("INVALID",state.quality);
        assertTrue(state.response(sample(9),4,at)); assertEquals("GOOD",state.quality);
    }
    @Test void handlesExactOfflineBoundaryWithoutRealSleep() {
        var state=new DeviceState(0,true,null);
        state.watch(9_999_000_000L,settings); assertEquals("UNKNOWN",state.connection);
        state.watch(10_000_000_000L,settings); assertEquals("OFFLINE",state.connection); assertNull(state.sample);
        state.response(sample(1),11_000_000_000L,at);
        state.watch(20_999_000_000L,settings); assertEquals("ONLINE",state.connection);
        state.watch(21_000_000_000L,settings); assertEquals("OFFLINE",state.connection);
        assertEquals(19,state.sample.batteryPercent()); assertEquals("STALE",state.quality);
    }
    @Test void heartbeatFreezeIsOnlineButStale() {
        var state=new DeviceState(0,true,null);
        state.response(sample(1),0,at);
        state.response(sample(1),9_999_000_000L,at.plusSeconds(9));
        state.watch(9_999_000_000L,settings); assertEquals("GOOD",state.quality);
        state.watch(10_000_000_000L,settings);
        assertEquals("ONLINE",state.connection); assertEquals("STALE",state.quality);
        assertEquals(at,state.lastFreshAt);
        assertTrue(state.response(sample(2),11_000_000_000L,at.plusSeconds(11)));
        assertEquals("GOOD",state.quality);
    }
    @Test void restartAndDisableDoNotTrustSavedOnlineState() {
        var restarted=new DeviceState(0,true,sample(10));
        assertEquals("UNKNOWN",restarted.connection); assertEquals("STALE",restarted.quality); assertNull(restarted.heartbeat);
        assertTrue(restarted.response(sample(10),1,at));
        var disabled=new DeviceState(0,false,sample(10)); disabled.watch(20_000_000_000L,settings);
        assertEquals("DISABLED",disabled.connection);
    }
    @Test void historyUsesLastSuccessfulSaveAndKeepsGaps() {
        var state=new DeviceState(0,true,null); assertTrue(state.historyDue(0,settings));
        state.historyNanos=0L;
        assertFalse(state.historyDue(1_000_000_000L,settings));
        assertFalse(state.historyDue(9_000_000_000L,settings));
        assertTrue(state.historyDue(10_000_000_000L,settings));
    }
    @Test void candidateAndRestartKeepCommittedValuesIndependent() {
        var committed=new DeviceState(0,true,null);
        committed.response(sample(1),0,at);
        committed.historyNanos=0L;
        var candidate=committed.copy();
        candidate.response(sample(8),11_000_000_000L,at.plusSeconds(11));
        candidate.historyNanos=11_000_000_000L;
        assertEquals(1,committed.heartbeat);
        assertEquals(at,committed.lastFreshAt);
        assertEquals(0L,committed.historyNanos);
        assertTrue(committed.copy().response(sample(8),12_000_000_000L,at.plusSeconds(12)));
        var disabled=committed.restart(12_000_000_000L,false);
        assertEquals(at,disabled.lastResponseAt);
        assertEquals(at,disabled.lastFreshAt);
        assertNull(disabled.responseNanos);
        assertNull(disabled.heartbeat);
    }
    @Test void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,() -> MonitorSettings.from(new MockEnvironment().withProperty("poll.max-attempts","3")));
        assertThrows(IllegalArgumentException.class,() -> MonitorSettings.from(new MockEnvironment().withProperty("alarm.battery-recover-at-least","19")));
    }
}
