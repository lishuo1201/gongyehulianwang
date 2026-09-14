package com.example.agv.monitoring;

import java.time.Instant;

/**
 * Device identity：T01/T02，id是数据库业务身份，host + port + unitId决定实际读取目标。
 * deviceCode用于业务识别，name用于显示；改名不会把历史或告警转移给另一台车。
 * Endpoint is fixed：本项目登记后不允许修改连接元组；换采集目标应停用旧登记并新建，保留历史归属。
 * configRevision用于拒绝配置变化前启动的旧任务结果；它不是设备心跳，也不是快照版本。
 */
public record Device(long id, String deviceCode, String name, String host, int port, int unitId,
                     boolean enabled, long configRevision, Instant createdAt, Instant updatedAt) { }
