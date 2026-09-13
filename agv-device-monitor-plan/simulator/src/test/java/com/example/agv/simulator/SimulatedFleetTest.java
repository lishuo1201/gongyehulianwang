package com.example.agv.simulator;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static com.example.agv.simulator.SimulatedFleet.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulatedFleetTest {
    @Test
    void createsIndependentDefaultsAndDetachedSnapshots() {
        var fleet = new SimulatedFleet();
        assertArrayEquals(new int[] {1, 1, 76, 1200, 3, 7, 0, 0}, fleet.snapshot(1).registers());
        assertArrayEquals(new int[] {1, 2, 23, 0, 9001, 9001, 0, 0}, fleet.snapshot(2).registers());
        assertArrayEquals(new int[] {1, 0, 88, 0, 5, 5, 0, 0}, fleet.snapshot(3).registers());
        fleet.snapshot(1).registers()[2] = 101;
        assertEquals(76, fleet.snapshot(1).registers()[2]);
        assertThrows(IllegalArgumentException.class, () -> fleet.snapshot(0));
        assertThrows(IllegalArgumentException.class, () -> fleet.changeScenario(4, NORMAL));
        assertThrows(IllegalArgumentException.class, () -> fleet.changeScenario(1, null));
    }

    @Test
    void advancesAndWrapsHeartbeatWithoutResettingItOnScenarioChanges() {
        var fleet = new SimulatedFleet();
        for (int i = 0; i < 65535; i++) {
            fleet.tick();
        }
        assertEquals(65535, fleet.snapshot(1).registers()[7]);
        assertEquals(65535, fleet.changeScenario(1, FAULT).registers()[7]);
        fleet.tick();
        assertEquals(0, fleet.snapshot(1).registers()[7]);
        assertEquals(0, fleet.snapshot(2).registers()[7]);
        fleet.tick();
        assertEquals(1, fleet.snapshot(1).registers()[7]);
        fleet.reset();
        assertEquals(0, fleet.snapshot(1).registers()[7]);
        assertEquals(NORMAL, fleet.snapshot(1).mode());
    }

    @Test
    void cyclesOnlyTheNormalFirstCarsPosition() {
        var fleet = new SimulatedFleet();
        for (int expected : new int[] {4, 5, 6, 7, 3}) {
            fleet.tick();
            assertEquals(expected, fleet.snapshot(1).registers()[4]);
            assertEquals(9001, fleet.snapshot(2).registers()[4]);
            assertEquals(5, fleet.snapshot(3).registers()[4]);
        }
    }

    @Test
    void freezesLastValidBlockAfterInvalidWithoutFreezingOtherCars() {
        var fleet = new SimulatedFleet();
        fleet.tick();
        int[] lastValid = fleet.changeScenario(1, FAULT).registers();
        fleet.changeScenario(1, INVALID);
        fleet.tick();
        assertEquals(101, fleet.snapshot(1).registers()[2]);
        assertArrayEquals(lastValid, fleet.changeScenario(1, FROZEN).registers());
        fleet.tick();
        assertArrayEquals(lastValid, fleet.snapshot(1).registers());
        assertEquals(3, fleet.snapshot(2).registers()[7]);
        var thawed = fleet.changeScenario(1, NORMAL);
        assertEquals(76, thawed.registers()[2]);
        assertEquals(2, thawed.registers()[7]);
        fleet.tick();
        assertEquals(3, fleet.snapshot(1).registers()[7]);
    }

    @Test
    void rebuildsDefaultsInsteadOfLeakingPreviousScenarioValues() {
        var fleet = new SimulatedFleet();
        fleet.changeScenario(1, FAULT);
        assertArrayEquals(new int[] {1, 1, 19, 1200, 3, 7, 0, 0},
                fleet.changeScenario(1, LOW_BATTERY).registers());
        assertEquals(76, fleet.changeScenario(1, NORMAL).registers()[2]);
        fleet.changeScenario(2, LOW_BATTERY);
        assertEquals(23, fleet.changeScenario(2, NORMAL).registers()[2]);
        fleet.changeScenario(2, SILENT);
        fleet.tick();
        assertEquals(1, fleet.snapshot(2).registers()[7]);
        assertEquals(SILENT, fleet.snapshot(2).mode());
    }

    @Test
    void concurrentUpdatesNeverExposeMixedRegisters() throws Exception {
        var fleet = new SimulatedFleet();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 4000; i++) {
                    fleet.changeScenario(1, (i & 1) == 0 ? FAULT : NORMAL);
                    fleet.tick();
                }
                return null;
            });
            var reader = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 4000; i++) {
                    var sample = fleet.snapshot(1);
                    int[] raw = sample.registers();
                    if (sample.mode() == FAULT) {
                        assertEquals(3, raw[1]);
                        assertEquals(0, raw[3]);
                        assertEquals(2, raw[6]);
                    } else {
                        assertEquals(1, raw[1]);
                        assertEquals(1200, raw[3]);
                        assertEquals(0, raw[6]);
                    }
                }
                return null;
            });
            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
