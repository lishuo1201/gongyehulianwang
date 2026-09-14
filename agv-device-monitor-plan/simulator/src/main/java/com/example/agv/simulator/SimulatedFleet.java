package com.example.agv.simulator;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * In-memory devices：T04，三台车各自持有场景、心跳、八寄存器与最近合法样本，不使用SQL维护模拟状态。
 * <p>Keep a coherent block：同车tick、切场景和snapshot使用同一把Unit锁；读取完整副本后立即释放。
 * 保证的是一台车的一组寄存器一致，不保证对三台车的列表查询都来自同一物理时刻。
 * <p>Scenario semantics：SILENT只影响协议是否响应，内存心跳仍推进；FROZEN停止整块更新；
 * INVALID继续心跳但给出电量101，让后端验证“通信正常但业务无效”，不能在后端放宽合法性校验。
 * Evidence：SimulatedFleetTest验证本车快照，SimulatorModbusIT验证真实线上响应和单车隔离。
 */
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

        /**
         * Change one experiment：FROZEN使用最近合法整组样本，不能只冻结heartbeat或沿用非法101电量。
         * 切其他场景从该车默认值重建并应用目标场景，保留内部心跳计数；只有reset归零。
         * NORMAL把1号电量恢复为76是模拟场景的明确约定，不是工业监控后端可随意修改设备值。
         */
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

        // Advance the device clock：一个tick推进一次16位心跳；65535之后回绕0，不是掉线或非法值。
        // SILENT still ticks：失去响应不等于设备内部停止工作，HTTP仍可切回正常场景。
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

        // Follow the point map：版本、运行状态、电量、mm/s速度、位置、目标、故障、心跳，顺序不可随意交换。
        // Battery hysteresis fixture：2号默认23未达到25恢复阈值；演示低电量恢复优先使用默认76的1号。
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
