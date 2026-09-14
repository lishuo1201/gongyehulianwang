# 03 数据模型

状态：T02 已提供 [V1 迁移](../backend/src/main/resources/db/migration/V1__create_monitor_tables.sql)，运行证据见 [测试计划](07-test-plan.md)。类型和索引变更同步本文件；采集与告警业务尚未实现。

## 1. 统一约定

- 数据库：MySQL 8.4.10，镜像摘要见设计决策；测试使用该摘要的专用临时容器。
- 表字符集 utf8mb4、排序规则 utf8mb4_0900_bin；编码、枚举和规则使用 ascii_bin，区分大小写。设备编码有大写与非空约束，应用需采用相同校验规则。
- 主键 `BIGINT` 自增；JSON 返回所有 ID 为字符串，避免 JavaScript 大整数精度问题。
- 时间列 `DATETIME(3)`，数据库连接和应用持久化均按 UTC；API ISO 8601 `Z`。
- 状态列采用 `VARCHAR`，由应用枚举、校验及迁移约束保证取值，不依赖数据库 ENUM。
- 不硬删除设备；引用使用外键，不启用级联删除。
- 所有实体初始化时间明确写入；业务字段没采集到时为 NULL，不编造默认值。

## 2. device：设备配置

| 列 | 类型 | 约束 / 说明 |
| --- | --- | --- |
| id | BIGINT | PK |
| device_code | VARCHAR(32) | 非空、唯一，例 AGV-001 |
| name | VARCHAR(64) | 非空 |
| host | VARCHAR(128) | 非空，必须在允许列表内 |
| port | INT | 1～65535 |
| unit_id | INT | Demo 允许 1～247；默认使用 1、2、3 |
| enabled | BOOLEAN | 非空，默认 true |
| config_revision | BIGINT | 非空，初值 0；名称/启停成功变更时递增 |
| created_at / updated_at | DATETIME(3) | 非空 |

唯一约束：`uk_device_code(device_code)`、`uk_device_endpoint(host, port, unit_id)`。连接元组创建后不可修改，第一版只更新名称和 enabled。

登记上限 10 台由单实例配置服务串行校验，数据库唯一约束仍负责防并发重复。不要宣称该上限检查支持多实例一致性。

## 3. device_snapshot：最新持久快照

| 列 | 类型 | 说明 |
| --- | --- | --- |
| device_id | BIGINT | PK、FK -> device.id，一台一行 |
| connection_status | VARCHAR(16) | UNKNOWN / ONLINE / OFFLINE / DISABLED |
| data_quality | VARCHAR(16) | UNKNOWN / GOOD / STALE / INVALID |
| run_state | VARCHAR(16) | UNKNOWN / IDLE / RUNNING / CHARGING / FAULT |
| battery_percent | SMALLINT | NULL 或 0～100 |
| speed_mm_s | INT | NULL 或 0～3000，存原始整数，API 转 m/s |
| position_code / target_code | INT | NULL 或 0～9999 |
| fault_code | INT | NULL 或 0～65535，不能用有符号 SMALLINT 截断 |
| heartbeat | INT | NULL 或 0～65535 |
| last_response_at | DATETIME(3) | 最后正常协议响应 |
| last_fresh_at | DATETIME(3) | 最后有效新样本 |
| snapshot_revision | BIGINT | 非空，初值 0，每次提交的快照变更递增 |
| updated_at | DATETIME(3) | 本行更新时刻，不等于采样时刻 |

行在登记设备时创建为 UNKNOWN；停用时为 DISABLED。重启时必须重新建立运行态健康判断，不能直接把保存的 ONLINE 当事实。旧值可加载，但置为待验证/陈旧视图。

## 4. device_sample_history：10 秒趋势采样

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | PK |
| sample_key | CHAR(36) | 唯一 UUID，识别同一个已解码样本，事务重试复用 |
| device_id | BIGINT | FK |
| sampled_at | DATETIME(3) | 后端接受新样本的时间，不冒充设备内置时钟 |
| run_state | VARCHAR(16) | 有效运行状态 |
| battery_percent | SMALLINT | 0～100 |
| speed_mm_s | INT | 0～3000 |
| position_code / target_code | INT | 0～9999 |
| fault_code / heartbeat | INT | 0～65535 |
| created_at | DATETIME(3) | 持久化时刻 |

全部测量字段非空，仅保存有效新样本。心跳重复、数据非法和设备离线时不补 0。

索引：`uk_sample_key(sample_key)`、`idx_sample_device_time(device_id, sampled_at, id)`、`idx_sample_time(sampled_at, id)`。前者用于设备时间范围查询，后者用于分批保留期清理。

3 台设备每 10 秒一条，稳定采样 7 天约 181,440 条；这是容量估算，不是实际压测。事件表另计。第一次采样和重启可能产生额外边界样本。

## 5. device_state_event：离散状态变化

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | PK |
| device_id | BIGINT | FK |
| event_type | VARCHAR(24) | CONNECTION / DATA_QUALITY / RUN_STATE / FAULT_CODE |
| old_value / new_value | VARCHAR(32) | 首次 old_value 可为 NULL |
| occurred_at | DATETIME(3) | 判定发生时间 |
| reason | VARCHAR(128) | 如 TIMEOUT、DISABLED、VALID_SAMPLE |

索引：`idx_event_device_time(device_id, occurred_at, id)`。一轮可以产生多个不同维度事件；值没变不能重复记录。heartbeat 不在事件类型中。

## 6. alarm：告警事件和活动槽

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | PK |
| device_id | BIGINT | FK |
| rule_code | VARCHAR(32) | LOW_BATTERY / DEVICE_FAULT / DEVICE_OFFLINE / DATA_STALE |
| severity | VARCHAR(16) | WARNING / CRITICAL；低电量/陈旧 WARNING，其余 CRITICAL |
| status | VARCHAR(16) | ACTIVE / RECOVERED / SUPPRESSED |
| active_slot | TINYINT | ACTIVE 必须为 1，其余必须为 NULL |
| trigger_value | VARCHAR(64) | 触发时的电量、故障码或超时描述 |
| last_value | VARCHAR(64) | 最近异常观测值；MySQL 保留字，SQL 中须使用反引号引用 |
| triggered_at | DATETIME(3) | 第一次触发 |
| last_observed_at | DATETIME(3) | 最近满足触发/持续条件的观测 |
| acknowledged_at | DATETIME(3) | 可空，首次确认时间 |
| recovered_at | DATETIME(3) | 可空，只在规则证明恢复时填 |
| closed_at | DATETIME(3) | 可空，RECOVERED / SUPPRESSED 的结束时间 |
| close_reason | VARCHAR(32) | 可空：RULE_RECOVERED / DEVICE_DISABLED |

唯一索引 `uk_alarm_active(device_id, rule_code, active_slot)`：利用非活动行 active_slot 为 NULL 的设计，保留多次历史事件，同时限制每设备每规则只有一个 active_slot=1。T02/T08 必须用真实 MySQL 验证此约束和并发行为，不能只依赖 H2 测试。

补充索引：`idx_alarm_device_time(device_id, triggered_at, id)`、`idx_alarm_status_time(status, triggered_at, id)`。

V1 CHECK 显式保证 ACTIVE 对应非空 active_slot=1，其他状态对应 NULL，避免 MySQL CHECK 的 UNKNOWN 语义绕过约束。另校验规则/严重级别、恢复与关闭字段及时间顺序；应用判定与事务处理已在T08实现，P1复用同一结构处理DATA_STALE。确认不修改 active_slot。停用设备必须在同一事务中更新快照并关闭其活动槽。

## 7. 写入与查询约束

- 不把一秒内三个设备的采集放进同一个长事务；每设备独立短事务。
- 告警并发插入冲突应回滚并有限重试/重读，不吞异常伪装插入成功。
- 历史查询必须带 deviceId、from、to；默认最多 24 小时，最大 7 天。
- 使用 `sampled_at DESC, id DESC` 确定性排序，分页从 1 开始，默认 20、最大 100。
- 用参数化 SQL；不能拼接用户输入的排序列、host 或时间值。
- 7 天自动清理仅处理 `device_sample_history`，T15 实现分批删除；告警和状态事件第一版保留，不隐式级联清理。

## 8. 迁移与初始数据

- T02：V1 建表和约束；执行两次迁移不应重建表或重复种子数据。
- demo 初始数据：AGV-001/002/003，host 为 `simulator`、port 1502、unitId 为 1/2/3。
- 种子通过 demo profile 的幂等初始化器生成，不能把特定环境 host 硬编码进不可变通用迁移。
- 业务代码使用应用账号，迁移账号权限按实际环境配置；不要在仓库写真实密码。
- 后续结构变更新建 V2、V3，不修改已经落地的 V1。

## 9. T02 已落实的初始化边界

- `DemoInitializer` 仅在 demo profile 生效，等 Flyway 迁移完成后执行；台账与未知快照在同一短事务初始化。
- 按 device_code 查询时包含停用设备；已有记录不覆盖名称、enabled、连接、revision、时间或快照。种子连接不一致时明确警告，数据库连接配置优先。
- 缺失设备才插入；连接被其他编码占用或登记数量超限时抛出错误并回滚整批初始化，不吞异常或覆盖其他车辆。
- `DemoProperties` 在写库前验证 seed-host/seed-port 的允许列表与范围；默认值位于 application-demo.properties。host 允许列表属于应用配置，数据库只约束非空与连接唯一。
- V1 为五表提供外键、枚举/数值范围和索引；历史样本还约束故障码与运行状态一致，状态事件拒绝新旧值相同。应用API在T09实现校验并拒绝修改连接元组；数据库迁移本身不承诺阻止管理员直接改连接列。
- JDBC 连接使用 UTC 会话与时区设置，初始化写 UTC_TIMESTAMP(3)；业务时间仍须在后续采集任务中按协议语义产生。
- 测试直接核对 MySQL 唯一约束错误 1062、外键错误 1451/1452、CHECK 错误 3819；本版 JDBC/Spring 组合可能将 CHECK 违规映射为 UncategorizedSQLException，不能仅靠异常类名判断已正确拦截。
