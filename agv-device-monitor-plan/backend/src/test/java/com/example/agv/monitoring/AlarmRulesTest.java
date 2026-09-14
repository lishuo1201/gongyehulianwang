package com.example.agv.monitoring;

import com.example.agv.protocol.DeviceSample;
import com.example.agv.protocol.RunState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import static com.example.agv.monitoring.AlarmRules.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class AlarmRulesTest {
    private final MonitorSettings settings=MonitorSettings.from(new MockEnvironment());
    private DeviceState state(int battery,int fault) {
        var state=new DeviceState(0,true,null);
        state.response(new DeviceSample(1,fault==0 ? RunState.RUNNING : RunState.FAULT,battery,
                fault==0 ? 1200 : 0,3,7,fault,1),0,Instant.EPOCH);
        return state;
    }
    @Test void lowBatteryHasHysteresisAndRequiresNewTrustedData() {
        assertEquals(NONE,AlarmRules.decide("LOW_BATTERY",state(20,0),true,false,settings));
        assertEquals(OPEN,AlarmRules.decide("LOW_BATTERY",state(19,0),true,false,settings));
        assertEquals(OBSERVE,AlarmRules.decide("LOW_BATTERY",state(20,0),true,true,settings));
        assertEquals(OBSERVE,AlarmRules.decide("LOW_BATTERY",state(24,0),true,true,settings));
        assertEquals(RECOVER,AlarmRules.decide("LOW_BATTERY",state(25,0),true,true,settings));
        assertEquals(NONE,AlarmRules.decide("LOW_BATTERY",state(25,0),false,true,settings));
        var invalid=state(25,0); invalid.quality="INVALID";
        assertEquals(NONE,AlarmRules.decide("LOW_BATTERY",invalid,true,true,settings));
        invalid.quality="STALE";
        assertEquals(NONE,AlarmRules.decide("LOW_BATTERY",invalid,true,true,settings));
    }
    @Test void offlineAndDisableCannotRecoverBusinessAlarms() {
        var state=state(19,999);
        assertEquals(OPEN,AlarmRules.decide("DEVICE_FAULT",state,true,false,settings));
        state.connection="OFFLINE"; state.quality="STALE";
        assertEquals(OPEN,AlarmRules.decide("DEVICE_OFFLINE",state,false,false,settings));
        assertEquals(NONE,AlarmRules.decide("DEVICE_FAULT",state,false,true,settings));
        assertEquals(NONE,AlarmRules.decide("LOW_BATTERY",state,false,true,settings));
        state.connection="UNKNOWN";
        assertEquals(NONE,AlarmRules.decide("DEVICE_OFFLINE",state,false,true,settings));
        state.connection="ONLINE";
        assertEquals(RECOVER,AlarmRules.decide("DEVICE_OFFLINE",state,false,true,settings));
        state.connection="DISABLED";
        for (String rule:new String[]{"DEVICE_OFFLINE","DEVICE_FAULT","LOW_BATTERY","DATA_STALE"})
            assertEquals(SUPPRESS,AlarmRules.decide(rule,state,false,true,settings));
        assertEquals(RECOVER,AlarmRules.decide("DEVICE_FAULT",state(19,0),true,true,settings));
    }
    @Test void staleNeedsRepeatedHeartbeatAndOnlyFreshGoodCanRecover() {
        var state=state(76,0);
        state.quality="STALE";
        assertEquals(NONE,AlarmRules.decide("DATA_STALE",state,false,false,settings));
        state.repeatedHeartbeat=true;
        assertEquals(OPEN,AlarmRules.decide("DATA_STALE",state,false,false,settings));
        assertEquals(OBSERVE,AlarmRules.decide("DATA_STALE",state,false,true,settings));
        state.quality="INVALID";
        assertEquals(NONE,AlarmRules.decide("DATA_STALE",state,false,true,settings));
        state.connection="OFFLINE"; state.quality="STALE";
        assertEquals(NONE,AlarmRules.decide("DATA_STALE",state,false,true,settings));
        assertEquals(RECOVER,AlarmRules.decide("DATA_STALE",state(76,0),true,true,settings));
        var restarted=new DeviceState(0,true,state.sample);
        restarted.connection="ONLINE";
        assertEquals(NONE,AlarmRules.decide("DATA_STALE",restarted,false,false,settings));
    }
}
