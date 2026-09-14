package com.example.agv;

import java.util.concurrent.TimeUnit;

import com.digitalpetri.modbus.Modbus;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.tcp.Netty;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Keep the fast layer database-free: 此处只验证基础上下文，数据库接线由真实 MySQL IT 验证。
        properties = {"management.endpoint.health.group.readiness.include=readinessState", "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class BackendApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsApplicationContext() {
        assertNotNull(context.getBean(BackendApplication.class));
    }

    @Test
    void createsModbusTransportsWithManagedDependencies() {
        // Check dependency linkage: Boot 管理的 Netty 版本不同于 Modbus 原始依赖，验证初始化兼容性。
        // No network operations: 此处不连接设备或绑定端口，真实协议验收留待 T04/T05。
        try {
            var transport = NettyTcpClientTransport.create(config -> config.setHostname("127.0.0.1"));
            assertNotNull(ModbusTcpClient.create(transport));
            assertNotNull(NettyTcpServerTransport.create(config -> config.setBindAddress("127.0.0.1")));
        } finally {
            // Release shared resources: 释放本测试创建的线程池和计时器，避免影响后续用例。
            Netty.releaseSharedResources(1, TimeUnit.SECONDS);
            Modbus.releaseSharedResources(1, TimeUnit.SECONDS);
        }
    }
}
