package com.example.agv.simulator;

import java.util.List;

import org.springframework.stereotype.Component;

/** In-memory devices: 三台模拟车各有自己的锁、场景、心跳和最近有效快照，不使用数据库。 */
@Component
public class SimulatedFleet {
    public enum Scenario { NORMAL, LOW_BATTERY, FAULT, SILENT, FROZEN, INVALID }

    /** Detached snapshot: 数组是锁内复制的副本，调用方修改它不会修改设备。 */
    public record Snapshot(int unitId, Scenario mode, int[] registers) { }

    private final List<Unit> units = List.of(new Unit(1), new Unit(2), new Unit(3));

    public Snapshot snapshot(int unitId) {
        return unit(unitId).snapshot();
    }

    public List<Snapshot> snapshots() {
        return units.stream().map(Unit::snapshot).toList();
    }

    public Snapshot changeScenario(int unitId, Scenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("mode is required");
        }
        return unit(unitId).change(scenario);
    }

    public void tick() {
        units.forEach(Unit::tick);
    }

    public List<Snapshot> reset() {
        units.forEach(Unit::reset);
        return snapshots();
    }

    private Unit unit(int id) {
        if (id < 1 || id > 3) {
            throw new IllegalArgumentException("unitId must be 1, 2 or 3");
        }
        return units.get(id - 1);
    }

    private static final class Unit {
        private final int id;
        private Scenario mode;
        private int heartbeat;
        private int[] registers;
        private int[] lastValid;

        private Unit(int id) {
            this.id = id;
            reset();
        }

        synchronized Snapshot snapshot() {
            // Copy under the same lock: 场景与八个寄存器同时取样，序列化和网络发送不持有此锁。
            return new Snapshot(id, mode, registers.clone());
        }

        synchronized Snapshot change(Scenario next) {
            if (next == Scenario.FROZEN) {
                // Freeze valid history: INVALID 的101不能污染用于冻结的最近有效快照。
                registers = lastValid.clone();
            } else {
                // Rebuild the scenario: 从本车默认值重建，保留心跳；仅 reset 将心跳归零。
                registers = defaults();
                registers[7] = heartbeat;
                if (next == Scenario.LOW_BATTERY) {
                    registers[2] = 19;
                } else if (next == Scenario.FAULT) {
                    registers[1] = 3;
                    registers[3] = 0;
                    registers[6] = 2;
                } else if (next == Scenario.INVALID) {
                    // Inject on output state: 仅模拟器制造非法原始值，后端的合法样本校验保持严格。
                    registers[2] = 101;
                }
                if (next != Scenario.INVALID) {
                    lastValid = registers.clone();
                }
            }
            mode = next;
            return snapshot();
        }

        synchronized void tick() {
            if (mode == Scenario.FROZEN) {
                return;
            }
            heartbeat = (heartbeat + 1) & 0xffff;
            registers[7] = heartbeat;
            if (mode == Scenario.NORMAL && id == 1) {
                registers[4] = registers[4] == 7 ? 3 : registers[4] + 1;
            }
            if (mode != Scenario.INVALID) {
                lastValid = registers.clone();
            }
        }

        synchronized void reset() {
            heartbeat = 0;
            mode = Scenario.NORMAL;
            registers = defaults();
            lastValid = registers.clone();
        }

        private int[] defaults() {
            return switch (id) {
                case 1 -> new int[] {1, 1, 76, 1200, 3, 7, 0, 0};
                case 2 -> new int[] {1, 2, 23, 0, 9001, 9001, 0, 0};
                case 3 -> new int[] {1, 0, 88, 0, 5, 5, 0, 0};
                default -> throw new IllegalStateException("Unexpected unitId: " + id);
            };
        }
    }
}
