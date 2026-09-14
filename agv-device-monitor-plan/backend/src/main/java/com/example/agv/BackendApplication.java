package com.example.agv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend entry point：T01，启动数据库迁移、监控业务与同源HTTP页面；不在此处创建模拟设备进程。
 * Learning route：T02看DemoInitializer，T03看protocol，T05看collector，T06～T10看monitoring和stream，
 * T11看static/monitor.js，T15看HistoryRetention。完整任务映射见docs/05-development-plan.md。
 * 启动时必须提供专用MySQL配置；demo profile只负责补充演示台账，不代表使用假数据库。
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
