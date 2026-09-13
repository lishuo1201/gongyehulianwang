# 07 测试与验收计划

状态：T01 的 A01、T02 的 A02 及数据库约束验证已通过；T03 的 A03、A04 解码拒绝层、A11 心跳比较层已通过。T04 的 A05/A06 服务端与 A07 单车隔离层已通过；A04/A11 的采集状态更新仍待 T06，A18 业务告警处理仍待 T08。

## 1. 测试层次

| 层次 | 范围 | 环境 |
| --- | --- | --- |
| 单元测试 | 字节解码、数据校验、状态、时钟、告警、采样策略 | 无网络、无数据库，确定性时钟 |
| TCP 集成测试 | 真实 Modbus 客户端/服务端、地址/Unit ID/超时、连接释放 | 本机临时监听端口；禁止只 Mock |
| MySQL 集成测试 | 迁移、唯一约束、活动告警、事务与索引相关查询 | Testcontainers 或等价隔离 MySQL；禁止代用 H2 自证 |
| HTTP/SSE 测试 | 参数、错误码、分页、快照和重连 | 应用测试实例 |
| 端到端验收 | Compose、页面、故障与恢复 | 本机独立测试项目/卷，不动个人数据 |

命名：单测 `*Test` 由 Surefire；集成 `*IT` 由 Failsafe。T01/T02 设置插件和运行策略。`./mvnw test` 为快速层；`./mvnw verify` 应实际包含集成测试。环境不足时整体标 BLOCKED，不能悄悄跳过 IT 后报告“全部通过”。

## 2. P0 验收用例

| ID | 场景 / 输入 | 预期结果 | 对应任务 |
| --- | --- | --- | --- |
| A01 | Java 17 编译两个模块 | 构建与基础测试通过，依赖版本可追溯 | T01 |
| A02 | 空数据库迁移后再启动一次 | 5 张表有效，无重复种子和覆盖 | T02 |
| A03 | 固定 16 字节样本、1200 速度、65535 心跳 | 得到 RUNNING、76%、1.2m/s、heartbeat=65535 | T03 |
| A04 | 协议版本!=1、电量101、故障状态矛盾 | 数据 INVALID，不更新可信业务值 | T03、T06 |
| A05 | 读取 unit 1、2、3 的 offset 0/count 8 | 返回独立、真实 TCP 响应 | T04、T05 |
| A06 | 写功能、超范围地址、未知 unit | 写/地址明确拒绝，未知 unit 按约定不响应 | T04、T05 |
| A07 | 单 unit SILENT，其他 unit 正常 | 总轮次有界，正常设备继续更新，同设备同时读取数<=1 | T05、T06 |
| A08 | 最后响应 t=0；检测 t=9.999 与 t=10 | 前者不离线，后者 OFFLINE；只产生一个离线事件 | T06 |
| A09 | 启动后从未收到任何响应 | 从 enabledAt 计时，10 秒后 OFFLINE，数值仍 null | T06 |
| A10 | FROZEN 保持有响应、heartbeat 不变 | ONLINE；10 秒后 STALE，lastFreshAt 不推进 | T06 |
| A11 | heartbeat 65535→0、首次 heartbeat=0 | 都被正确接受为有效新样本 | T03、T06 |
| A12 | 在途读期间停用、再重复停用 | DISABLED 不被晚到数据覆盖；重复停用幂等 | T06、T09 |
| A13 | 新样本 t=0、1、9、10；随后陈旧 | 历史只保存 t=0、10；陈旧不补 0；快照按有效样本更新 | T07 |
| A14 | 强制事务回滚 | 快照/应写历史/事件/告警一致回滚；无已提交状态推送 | T07、T08、T10 |
| A15 | 初始电量20，再19，再24，再25 | 20不触发；19触发；24仍ACTIVE；25恢复 | T08 |
| A16 | 同一故障连续100次；确认；仍故障 | 仅1条ACTIVE，首次确认时间稳定，不自动恢复 | T08 |
| A17 | 故障恢复后再次出现 | 第一条RECOVERED，第二次新告警ID，活动槽唯一 | T08 |
| A18 | 两个并发触发同设备同规则 | 数据库中最多1条ACTIVE，冲突被正确处理 | T02、T08 |
| A19 | 低电量/故障时掉线，再停用 | 掉线不自动恢复原告警；停用后SUPPRESSED并释放活动槽 | T08 |
| A20 | 后端重启时数据库有旧ONLINE和ACTIVE | 初始待重新验证，首次新样本后确认状态，不重复活动告警 | T07、T08 |
| A21 | 重复编码、任意host、非法字段、修改连接元组 | 400/409符合契约；不尝试任意网络连接 | T09 |
| A22 | 历史空范围、超过7天、分页边界 | 空items；非法跨度400；时间左闭右开，排序稳定 | T09 |
| A23 | SSE 初次连接、断开、重连 | 全量快照、保活与当前恢复；释放旧连接资源 | T10 |
| A24 | 正常/离线/未知/INVALID页面 | 数值、连接、质量分别显示；null不是0；断线有提示 | T11 |
| A25 | Compose新环境启动及普通重启 | 正确依赖顺序、服务名可达、旧数据库保留、默认仅本机页面可访问 | T12 |

P0 用例可以由多个测试共同覆盖；每个 ID 都需对应证据。时间边界测试不得真实 sleep 10 秒，使用可控时间；TCP 超时测试用真实网络并设置总测试上限。

## 3. P1 验收

| ID | 场景 | 预期 |
| --- | --- | --- |
| B01 | 3台运行30分钟，期间单台 SILENT | 线程/连接/队列不持续无界增长，其他设备继续被采集 |
| B02 | 造8天前与7天内的历史 | 清理只删超过截止时间的采样，单批<=1000，告警/事件不动 |
| B03 | 暂停数据库写入再恢复 | readiness不就绪、明确持久化错误，不把它误当设备离线；恢复后继续，缺口明示 |
| B04 | 后端、模拟器分别重启 | 状态重验、活动告警不重复、定时任务和连接恢复 |
| B05 | 20条SSE连接及超限、慢读、反复关页 | 超限拒绝，缓冲有界，清理有效，采集不被推送线程卡住 |
| B06 | FROZEN→NORMAL | DATA_STALE触发和恢复，始终不同于离线告警 |
| B07 | 按README从头演示 | 无隐含步骤；截图与代码版本一致；限制可复述 |

以上是验收目标，不是已实现的容量或性能指标。需要记录操作系统、JDK、CPU/内存、依赖/镜像版本和测试时长，才能解释结果适用范围。

## 4. 3～5 分钟手工验收脚本

1. 打开页面，确认 3 台数据持续变化，连接 ONLINE、质量 GOOD。
2. 对 unit 1 设置 LOW_BATTERY，看到电量19和一条告警；多次刷新不增加活动事件。
3. 点击确认，看到 acknowledgedAt，但 status 仍为 ACTIVE。
4. 切回 NORMAL，下一有效新样本电量>=25，告警恢复。
5. 对 unit 2 设置 FAULT，出现故障告警，再恢复 NORMAL。
6. 对 unit 3 设置 SILENT；记录最后响应时间，约10～12秒后页面显示OFFLINE和离线告警，其他两台继续更新。
7. 恢复 NORMAL，在线恢复；查询历史能看到采样间隔和断线空缺。
8. 若实现 P1，再演示 FROZEN：通信仍在线但数据陈旧。

## 5. 完成定义与证据表

没有真实运行过的用例写 NOT_RUN；环境不足写 BLOCKED；不能填“理论通过”。

| 用例 ID | 状态 | 运行环境 / 命令 | 证据路径 / 输出摘要 | 日期 |
| --- | --- | --- | --- | --- |
| A01 | PASS | Java 17.0.20；`timeout 60s ./mvnw verify` | 两模块 `target/surefire-reports/` 和 `target/failsafe-reports/`；详见 T01 交接 | 2026-09-11 |
| A02 | PASS | Java 17、MySQL 8.4.10、Docker 29.6.2；`timeout 60s ./mvnw -B -ntp verify` | `backend/target/failsafe-reports/TEST-com.example.agv.BackendApplicationIT.xml`：空库迁移、真实应用重启、用户配置与快照保留、重复迁移不再执行 | 2026-09-13 |
| A18（数据库层） | PASS | 同上；两个独立 JDBC 事务由 barrier 同步插入 | `concurrentInsertsLeaveOneActiveAlarmAndLoserCanReread`：一个提交成功，一个收到 1062 后回滚并重读到活动记录 | 2026-09-13 |
| A18（业务处理） | NOT_RUN | T08 尚未实现 | 未验证业务服务的有限重试及整批采样持久化 | — |
| A03 | PASS | Java 17；`timeout 60s ./mvnw -o -B -ntp test` | `backend/target/surefire-reports/TEST-com.example.agv.protocol.RegisterCodecTest.xml`；独立固定字节解码为 RUNNING、76%、1.2m/s、65535，编码另与固定字节比较 | 2026-09-13 |
| A04（解码拒绝层） | PASS | 同上 | 版本、枚举、长度、数值范围及跨字段冲突被 IllegalArgumentException 明确拒绝；不能构造无效 DeviceSample | 2026-09-13 |
| A04（状态/持久化层） | NOT_RUN | T06/T07 尚未实现 | INVALID 状态更新、旧业务值保留尚未通过业务运行验证 | — |
| A11（解码与比较层） | PASS | 同上 | 解码 65535 与 0；hasNewHeartbeat 接受首次 null、回绕和不同值，拒绝把相同心跳当新观测 | 2026-09-13 |
| A11（采集状态层） | NOT_RUN | T06 尚未实现 | 尚未验证 lastFreshAt、10 秒 STALE 与快照更新 | — |
| A05（模拟器服务端） | PASS | Java 17、Modbus 2.1.6；`timeout 60s ./mvnw -o -B -ntp -pl simulator -am verify` | `SimulatorModbusIT`：独立JDK socket读取三个Unit，校验固定PDU、MBAP事务号/长度/Unit及部分读取 | 2026-09-13 |
| A06（模拟器服务端） | PASS | 同上 | 写与其他不支持功能返回01，地址越界02，数量/长度非法03；未知Unit无响应，同一连接后续仍可读；空PDU关闭自身连接 | 2026-09-13 |
| A05/A06（后端适配器） | NOT_RUN | T05 尚未实现 | 上述原始TCP探针不代替后端 DeviceClient 实现验证 | — |
| A07（模拟器隔离层） | PASS | 同上 | 同一TCP连接先请求SILENT的2号，再读1/3号成功；HTTP管理入口仍能恢复2号，无sleep阻塞 | 2026-09-13 |
| A07（采集调度层） | NOT_RUN | T05/T06 尚未实现 | 轮次有界、每设备in-flight与超时重试仍未验证 | — |
| A08～A10、A12～A17、A19～A25 | NOT_RUN | 尚未实现 | 局部约束、编解码及模拟场景证据不代替采集/告警业务用例 | — |
| B01～B07 | NOT_RUN | 尚未实现 | 无 | — |

实施时按实际用例拆行。截图和报告建议放 `docs/evidence/`，仅在确实产生后引用，不提前创建虚构内容。测试账号、凭证和内网地址需脱敏。

### T02 数据库层补充证据（2026-09-13）

- 最终命令：`timeout 60s ./mvnw -B -ntp verify`，总耗时 37.135 秒；14 个测试，0 失败、0 错误、0 跳过。后端快速测试 4 个、MySQL/HTTP IT 8 个，模拟器快速/HTTP 测试各 1 个；报告位于各模块 target 下，不提交生成报告。
- 固定 MySQL 摘要，Testcontainers 2.0.5 创建专用 agv_t02_test 数据库，动态生成非 root 应用账号与密码；数据库端口只绑定 127.0.0.1。数据源和 Flyway 参数均显式指向该容器；不使用个人库或可复用容器。结束后按测试标签确认没有残留 MySQL 容器，对应 Ryuk 也已移除。
- 验证设备编码/连接唯一、外键限制及未知快照；数值范围与枚举大小写；同一 sample_key 不重复；历史故障码与状态一致。
- 验证多个 RECOVERED/NULL 历史和不同规则的活动告警共存，同规则活动告警重复被拒；ACTIVE 的 NULL/0 槽位被拒，关闭字段一致性与 SUPPRESSED 不伪造恢复时间。
- 验证初始化新增设备与快照的整体事务：后续连接冲突或数量超限会撤销本轮已经插入的记录。重启保留名称、enabled、revision、原连接和完整快照；种子端口变化记录明确警告。无 demo profile 时不注册初始化器。
- 会话时区实际为 +00:00。代码使用 UTC_TIMESTAMP(3) 初始化时间；设备自身时间与后续采集时间的业务处理尚未实现。
- 初次验证发现并修正了带标签摘要的 Testcontainers 名称解析、last_value 保留字引用，以及 CHECK 错误的 Spring 异常分类假设。最终在新的临时 MySQL 上完整重跑通过；失败尝试不计作通过证据。


### T03 编解码层补充证据（2026-09-13）

- 命令：应用根目录执行 `timeout 60s ./mvnw -o -B -ntp test`；Java 17，使用现有离线依赖，53.285 秒 BUILD SUCCESS。
- 本轮 Surefire 共 53 个测试：后端 52 个（含 RegisterCodecTest 48 个）、模拟器 1 个，0 失败、0 错误、0 跳过。协议测试耗时 0.288 秒，报告为 `backend/target/surefire-reports/TEST-com.example.agv.protocol.RegisterCodecTest.xml`；报告为生成产物，不提交 Git。
- 固定 fixture 同时独立验证解码字段及编码字节；覆盖速度 0/500/1200/3000、位置与目标 0/9999、故障 0/1/999/65535、心跳 0/65535、四种合法运行状态以及合法低电量 19。非法输入覆盖空值、截断/多余字节、未知版本/状态、范围与字段组合冲突；构造器同样拒绝无效输入。
- 心跳比较验证首次 null、重复不推进、正常变化、回绕与重置；只证明比较规则，不证明 10 秒时钟阈值、状态迁移、告警时间更新或数据库写入。
- 既有后端上下文测试出现 Netty 事件循环未在1秒内关闭的 WARN，快速测试仍正常结束。未修改相关代码；本轮没有 HTTP/Modbus TCP 或 MySQL 集成运行证据，旧 Failsafe 报告不计入本轮。


### T04 模拟器补充证据（2026-09-13）

- 最终命令：`timeout 60s ./mvnw -o -B -ntp -pl simulator -am verify`，19.676秒；21个测试，0失败/错误/跳过。SimulatedFleetTest 6个、原上下文单测1个、SimulatorApplicationIT 4个、SimulatorModbusIT 10个。
- 当前报告：`simulator/target/surefire-reports/TEST-com.example.agv.simulator.SimulatedFleetTest.xml`、`simulator/target/failsafe-reports/TEST-com.example.agv.simulator.SimulatorApplicationIT.xml`、`simulator/target/failsafe-reports/TEST-com.example.agv.simulator.SimulatorModbusIT.xml`。仅模拟器模块及父项目参与本轮构建，不使用旧后端/Failsafe报告充数。
- Java 17；TCP与HTTP使用真实回环随机端口，独立JDK socket检查MBAP/PDU，不Mock、不引用backend编解码；无数据库或Docker。固定三车值、部分范围、65535编码、模式变更通过实际TCP读回。
- HTTP→内存场景→TCP完整验证 LOW_BATTERY、FAULT、INVALID、FROZEN、reset恢复；SILENT期间同连接其他Unit可读，HTTP可恢复所选Unit。正常心跳由每秒调度实际推进；FROZEN整块不变，INVALID后冻结最近有效快照。
- 并发测试覆盖设备锁保护的内存复制及持续模式切换期间的真实TCP读取；只证明这些测试场景，非容量基准或所有调度交错的形式证明。
- 生命周期测试验证关闭后已连接客户端EOF与端口连接失败，绑定已占用端口明确失败且原服务继续；空PDU产生预期错误日志并只关闭自身连接。非法管理JSON、模式、字段与Unit返回400。
- 首轮19个测试通过后，复核新增空PDU/绑定冲突检查并加强并发写持续性，最终重跑21个通过。未运行完整项目verify、后端MySQL/HTTP、采集状态机或业务告警；T04已生成六种测试场景，不代表这些后端功能已实现。
