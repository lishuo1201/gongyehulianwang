package com.example.agv.simulator;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Simulation controls：T04/T13/T19，供测试和演示切换六种场景，仅修改本进程内存。
 * /sim/v1/units可用于排错，但后端不能拿此HTTP返回代替Modbus测量；否则无法验证真实协议接入。
 * 切单车场景走PUT，reset显式恢复三台默认值和心跳；均不是实际AGV的控制或业务调度接口。
 */
@RestController
@RequestMapping("/sim/v1")
public class SimulatorController {
    private final SimulatedFleet fleet;

    public SimulatorController(SimulatedFleet fleet) {
        this.fleet = fleet;
    }

    @GetMapping("/units")
    public List<SimulatedFleet.Snapshot> units() {
        return fleet.snapshots();
    }

    @PutMapping("/units/{unitId}/scenario")
    public SimulatedFleet.Snapshot scenario(@PathVariable int unitId, @RequestBody Map<String, Object> body) {
        // Reject ambiguous input: 未知字段、缺失值、非字符串模式都不能被静默忽略或转换。
        if (body == null || body.size() != 1 || !(body.get("mode") instanceof String mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exactly one string field 'mode' is required");
        }
        try {
            return fleet.changeScenario(unitId, SimulatedFleet.Scenario.valueOf(mode));
        } catch (IllegalArgumentException invalidInput) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidInput.getMessage(), invalidInput);
        }
    }

    @PostMapping("/reset")
    public List<SimulatedFleet.Snapshot> reset() {
        return fleet.reset();
    }
}
