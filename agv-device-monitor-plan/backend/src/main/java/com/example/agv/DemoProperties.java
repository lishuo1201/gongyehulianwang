package com.example.agv;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local connection policy：T02/T09，明确允许采集的host和port，防止登记接口任意连接其他服务。
 * 这只是本地Demo的目标允许列表，不替代生产身份认证；已有设备以数据库中的连接元组为准。
 * seedHost/seedPort用于补缺初始化，maxDevices控制本项目演示容量，不代表已验证的生产吞吐量。
 */
@ConfigurationProperties("demo")
public record DemoProperties(String seedHost, int seedPort, List<String> allowedHosts,
                             List<Integer> allowedPorts, int maxDevices) {

    public DemoProperties {
        // Validate before database writes: 初始化配置非法时直接失败，不自动扩大允许访问的目标范围。
        if (seedHost == null || seedHost.isBlank() || seedHost.length() > 128
                || !seedHost.equals(seedHost.strip())
                || allowedHosts == null || !allowedHosts.contains(seedHost)) {
            throw new IllegalArgumentException("demo.seed-host must be a nonblank allowed host (max 128 chars)");
        }
        if (seedPort < 1 || seedPort > 65535 || allowedPorts == null || !allowedPorts.contains(seedPort)) {
            throw new IllegalArgumentException("demo.seed-port must be an allowed port between 1 and 65535");
        }
        if (maxDevices < 1) {
            throw new IllegalArgumentException("demo.max-devices must be positive");
        }
        allowedHosts = List.copyOf(allowedHosts);
        allowedPorts = List.copyOf(allowedPorts);
    }
}
