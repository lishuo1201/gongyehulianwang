package com.example.agv.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Independent simulator：T04，一个进程模拟三台AGV，共享TCP监听地址，通过Unit ID选择内部车辆。
 * HTTP管理接口只切换场景，后端测量必须通过Modbus TCP读取；不依赖后端数据库或采集服务启动。
 * Learning route：SimulatedFleet维护内存状态，SimulatorModbusServer处理协议，SimulatorController控制实验场景。
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
