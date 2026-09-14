# 07 测试与验收计划

状态：2026-09-14，P0/P1代码及分层工程验收全部完成，最终30分钟系统检查PASS；准确命令及限制见文末。历史证据表保留当时的NOT_RUN状态，不代表当前仍未实现。学习状态另见任务交接，代码通过不代表个人已掌握。

### 当前 P0 证据汇总

以下PASS限于表内列明的分层或组合用例，不意味着每种并发交错、真实厂商设备或生产能力均已验证。

| 用例 | 状态 | 实际证据 |
| --- | --- | --- |
| A01～A03 | PASS | Java17构建；BackendApplicationIT迁移/幂等；RegisterCodecTest独立固定字节 |
| A04～A06 | PASS | 编解码拒绝、DeviceState非法值基准；模拟器与DeviceClient真实TCP；浏览器INVALID保留可信值 |
| A07 | PASS | 真实TCP超时/恢复；MonitorConcurrencyIT单设备in-flight、队列拒绝；Compose单车SILENT时其他车更新 |
| A08～A11 | PASS | DeviceStateTest可控时间、首次无响应、冻结、回绕；MonitoringPersistenceIT精确9.999/10秒只记一次离线事件；浏览器/Compose状态场景 |
| A12 | PASS | 真实TCP响应延迟交付后停用/重启用，旧revision不覆盖；排队停用不发读；重复停用幂等 |
| A13～A14 | PASS | MonitoringPersistenceIT真实0/1/9/10采样、四表回滚、失败候选不发布、同心跳重试；SSE读取已提交缓存由HTTP测试覆盖 |
| A15～A17 | PASS | 规则测试20/19/24/25；MySQL低电量和故障999各100次观测、确认、恢复、新告警ID；真实浏览器低电量闭环 |
| A18～A20 | PASS | T02并发活动槽约束；最外层回滚后有限重试；停用SUPPRESSED；MonitorConcurrencyIT带ACTIVE重启不重复 |
| A21～A22 | PASS | MonitoringApiIT真实HTTP400/404/409/503及显式DTO；时间左闭右开、同时间ID排序、空页和分页上界 |
| A23 | PASS | 真实SSE首帧、保活、断开后20连接及超限503；浏览器离线/重连；视图第二次查询失败保留整体旧缓存 |
| A24 | PASS | 真实浏览器状态、未知值、表单、历史/事件、告警、390px布局；两张实测截图 |
| A25 | PASS | 最终镜像空卷启动、保留卷重启、默认端口/非root、开发映射更新及故障演练，最终PASS且独占资源清理 |

组合验证的边界：A14没有在同一事务回滚用例中同时持有网络SSE连接；A18并发约束和业务重试分别测试。SSE所有注册/关闭交错及极慢客户端压力未穷举，P1不因本表通过。

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
8. 演示FROZEN：通信仍在线但数据陈旧，出现DATA_STALE；INVALID不恢复，NORMAL的新有效提交恢复。

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


### P0 后端闭环补充证据（2026-09-14，整体未验收）

本节更新当前状态，不将历史 NOT_RUN 行或局部 PASS 等同于整个验收项完成。所有后端命令均设置60秒上限，测试代码可复跑；报告为生成产物，不提交 Git。

- 最终后端命令：`timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dit.test=MonitoringPersistenceIT,DeviceClientIT -Dfailsafe.failIfNoSpecifiedTests=false verify`。29.753秒，62单测 + 6集成测试，0失败/错误/跳过。
- HTTP/SSE 分批命令：`timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringApiIT -Dfailsafe.failIfNoSpecifiedTests=false verify`。27.391秒，10单测 + 1综合集成测试，0失败/错误/跳过。
- 本轮较早执行全项目 `timeout 60s ./mvnw -o -B -ntp test`，67个单测通过；随后新增的2个告警规则单测已包含在上述最终62个后端单测中。模拟器源码本轮未改动；未重跑其 TCP/HTTP 集成套件，也未运行完整项目 verify。
- 环境：Java 17.0.20、Spring Boot 4.1.1、Modbus 2.1.6，Testcontainers 创建固定 MySQL 8.4.10 摘要容器；随机凭据、动态回环端口、不复用个人数据库。测试独立 TCP fixture 使用 JDK socket，不调用生产编码器。

| 验证范围 | 当前证据 | 仍未证明的部分 |
| --- | --- | --- |
| A04/A08～A11 状态规则 | `DeviceStateTest` 8用例：非法值保留、9.999/10秒、冻结、回绕、首次心跳、副本隔离、重启时间及历史间隔 | 全调度链路的全部边界尚待补齐 |
| A05/A07 客户端层 | `DeviceClientIT` 5用例：真实解码、连接复用、非法值、异常响应、单车超时与其他车读取、错误Unit、晚到响应、拒绝连接及关闭 | protocolId、取消竞争、服务队列饱和/在途启停尚待验证 |
| A14 事务与缓存视图 | `MonitoringPersistenceIT` 在告警插入触发 SIGNAL，验证快照/历史/事件/告警全回滚，旧视图不变且 DEGRADED；通信时间独立保留；下一次相同心跳完整入库 | 尚未通过 SSE 网络订阅同时观测这一回滚过程 |
| A13/A18 保存及重试 | 同上：首次历史、失败后同心跳重试、sample_key重复不改快照；活动槽冲突前改名先回滚，第二次重读；设备编码冲突不重试 | 完整0/1/9/10秒落库序列和多采集任务并发仍待补齐 |
| A15/A19 告警规则 | `AlarmRulesTest` 验证20/19/24/25、可信新样本门槛、故障999、掉线不恢复业务告警、停用抑制；真实MySQL验证确认不恢复、重复确认与停用 | 连续100次、恢复后再次触发的完整数据库流程待补齐 |
| A12/A20 启停与重启 | 真实MySQL验证重复停用不改revision、别名不改Unit、停用保留时间、重启UNKNOWN/STALE且相同心跳重新接受 | 在途启停竞争及重启前ACTIVE保持场景待补齐 |
| A21/A22 HTTP | `MonitoringApiIT`：设备CRUD子集、资源ID/Unit类型、UTC/null/速度、无内部字段、编码/端点冲突409、未允许host/字段400、未知资源404、时间跨度及分页上限 | 时间边界排序、所有异常映射与容量登记仍待补齐 |
| A23 SSE 子集 | 同上：真实首次全量、保活、断开后重新占满20槽、第21连接503；最终日志无异常处理器失败和线程未终止提示 | 序号并发交错、刷新失败保留视图、慢客户端与重复重连仍待补齐 |
| A24/A25、面试展示材料 | NOT_RUN，尚无页面/Compose | 完整本机演示仍未完成 |

报告位于 `backend/target/surefire-reports/TEST-com.example.agv.monitoring.*.xml`，以及 `backend/target/failsafe-reports/TEST-com.example.agv.collector.DeviceClientIT.xml`、`TEST-com.example.agv.monitoring.MonitoringPersistenceIT.xml`、`TEST-com.example.agv.api.MonitoringApiIT.xml`。

首次 HTTP/SSE 测试虽断言通过，但日志暴露客户端断开后错误处理器尝试输出 JSON，触发转换异常；已修正并增加日志与连接容量检查，最终重新运行通过。MySQL 用例中的 `test alarm failure` 是受控失败注入，测试明确验证回滚与恢复，不将异常吞掉冒充成功。


### 协议取消与浏览器补充证据（2026-09-14）

- 命令：`timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=DeviceClientIT -Dfailsafe.failIfNoSpecifiedTests=false verify`，15.241秒，8单测 + 7真实TCP用例通过，无失败/错误/跳过。补充非零protocolId、错误事务号、2/17/19字节PDU、在途disconnect后本轮不重试、后续新读取可恢复。错误事务号测试中的库WARN为预期拒绝证据。
- `scripts/browser-check.js` 使用已有 Playwright CLI 和 Chromium 真实执行，最终返回 `result: PASS`。浏览器连接本地Java后端，后端连接真实模拟器TCP和固定摘要的专用MySQL容器。最后一轮通过后保存实际截图，未经图像编辑。
- A15/A17/A19 的低电量链路在浏览器与MySQL上验证：确认仍ACTIVE、恢复后RECOVERED、再次低电量使用新ID、非法/冻结数据不解除告警。A23验证浏览器离线立即提示、上线新建SSE并恢复全量；A24验证正常/无效/陈旧/离线、未知值及手机页面。
- 浏览器检查同时覆盖设备名称按文本显示（含HTML样式输入）、历史和事件查询、告警筛选、登记不允许host返回400、合法停用登记显示未知。预期的HTTP400控制台记录不视为运行错误；其余pageerror/console error会使脚本失败。
- 最终截图：[桌面](evidence/2026-09-14-monitor-desktop.png)、[手机](evidence/2026-09-14-monitor-mobile.png)。桌面视口1440×1080、手机390×844；保存的是全页截图。390px实测document.scrollWidth=390；表格在自身容器内滚动。
- 截图显示3台真实模拟器设备与2条停用的测试登记，不能据此声称已接入5台真实设备。最终浏览器运行使用后端静态资源目录覆盖指向当前源码；随后执行 `timeout 60s ./mvnw -o -B -ntp -DskipTests package`（2.610秒成功），逐字节确认JAR中index.html/monitor.css/monitor.js与已验证源码一致；此打包未重跑测试。Compose与真实厂商设备仍NOT_RUN。
- 修复轨迹：首轮在浏览器离线提示失败；修正后发现移动端日期输入框溢出2px及HTML pattern警告；进一步修正并完整重跑最终PASS。仅声明最终脚本实际覆盖的范围，不把Java打包或页面截图当作剩余并发、数据库故障、部署验证。

- 资源清理：浏览器 agv-p0 会话已停止，运行脚本收到中断后正常退出，并成功清理其独占Java进程、MySQL容器及匿名卷。没有操作个人数据库或其他浏览器会话。

### 健康检查与采样/告警边界（2026-09-14）

- `timeout 60s ./mvnw -o -B -ntp -pl simulator -am verify`：20.100秒，7单测+14真实TCP/HTTP集成测试通过，无失败/错误/跳过。新增readiness/liveness HTTP200、监听关闭后健康DOWN验证。
- `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false verify`：29.203秒，10单测+1综合IT通过，无失败/错误/跳过。
- A13补充真实落库：重启后第12秒首次样本，13/21秒仅更新快照，22秒新增历史，符合相对0/1/9/10秒。随后100次有效低电量观测保持同一活动告警及首次确认时间，lastObservedAt推进；不足历史间隔不补记录。覆盖A16的低电量规则；故障码连续观测由规则单测与后续验收分别说明。
- Docker Compose默认和开发配置解析通过；固定Java17镜像的两目标构建通过。构建使用跳过测试的打包命令，不能将镜像构建再计作测试通过。Compose实际部署结果另行记录。

### A25 Compose端到端证据（2026-09-14）

- 两镜像构建：`docker compose --env-file .env.example -p agv-p0-build build`，最终退出0。应用由固定摘要Temurin17编译、运行，运行JRE为17.0.20+8。镜像构建跳过测试；代码测试另计。
- 验收使用随机项目 `agv-check-0470ffff`、随机回环端口、临时随机应用/数据库凭据和独占命名卷。复用上述镜像，通过临时image覆盖保持同一构建；未读取个人.env或个人数据库。
- 默认配置执行 `up --no-build -d --wait --wait-timeout 120` 后：三台设备ONLINE/GOOD，烟测输出PASS/devices=3/activeAlarms=0；检查Docker实际PortBindings，仅backend发布回环8080，其余服务无宿主端口；两应用以10001:10001运行。
- 修改1号名称并停用，普通down保留卷，再up：ID、名称、enabled、unitId、configRevision不变，快照DISABLED。加载开发覆盖后依赖被重建、后端跟随重启，重新启用成功。
- 实际运行scenario.sh：1号LOW_BATTERY触发，确认后仍ACTIVE，NORMAL后RECOVERED；2号SILENT超过阈值后OFFLINE，3号仍ONLINE且心跳推进，恢复NORMAL后2号ONLINE。最终烟测PASS、0活动告警。
- 最终验收输出 `RESULT PASS`、`CLEANUP_PASS`，仅本轮独占容器/网络/卷被清理。运行记录为 `/tmp/agv-compose-check-result.log`，详细日志在临时项目目录；不提交随机凭据。复跑步骤见 docs/08-deployment.md 与 docs/09-interview-guide.md。
- 首次完整尝试在开发映射重建依赖后PATCH超时，已通过Compose原生depends_on.restart修复，随后从新空卷完整重跑通过。未宣称突然停库、自动重启或长时间运行已验证；这些属于P1。既有桌面/手机截图来自此前本地Java浏览器验收，不冒充本次Compose截图。

### 最终并发、HTTP与镜像回归（2026-09-14）

- 新增 `MonitorConcurrencyIT`：真实TCP结果只在交付给业务前受latch控制，不伪造成功响应；一工作线程/一队列槽确定性验证in-flight、队列拒绝释放、排队停用、旧revision结果丢弃及重新启用后继续采集。MySQL为独占容器。
- 同用例验证已保存ACTIVE告警跨后端重启仍为同一ID；在测试库临时重命名alarm，使视图的第二个查询失败，缓存设备与告警均保持旧值并显示DEGRADED，恢复表名和刷新后RUNNING。预期SQL错误日志属于注入证据。
- 首次并发断言虽通过，日志出现Netty事件线程先于断连回调关闭的错误；已修正为追踪待完成断连并有界等待后再关Netty。最终命令 `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitorConcurrencyIT,DeviceClientIT -Dfailsafe.failIfNoSpecifiedTests=false verify`：31.617秒，10单测+8IT通过，无事件线程终止/断连等待失败日志。之后补充的日志断言也在MonitorConcurrencyIT复跑通过。
- HTTP最终命令 `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringApiIT -Dfailsafe.failIfNoSpecifiedTests=false verify`：26.941秒，10单测+1综合IT通过。固定UTC边界、同时间多记录按ID降序、第三页为空、最大page、page0拒绝；此前所有HTTP/SSE断言继续执行。
- HTTP新增fixture首次误用本机时区的Timestamp导致空结果，改为DATETIME对应的LocalDateTime后重跑通过；没有为通过测试修改生产时间过滤。
- 关闭顺序修复后重新构建两镜像，并以新项目 `agv-check-3b1b0ebc` 从空卷重跑全部Compose流程，最终 `RESULT PASS` 和 `CLEANUP_PASS`，日志 `/tmp/agv-compose-final-result.log`。未启动长期演示服务，不留下测试数据库。
- 数据库最终补强：`timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false verify`，35.168秒，10单测+1综合IT通过。最后响应后9.999秒仍ONLINE，10秒OFFLINE且重复watch仅一条连接事件、历史不补零；低电量仍ACTIVE。故障999连续100次仅一条故障告警，确认后仍异常且保留首次确认，故障0恢复，再999产生新ID。


## 6. P1实现与回归证据（2026-09-14）

以下结果来自当前工作区代码，尚未提交。Maven均退出0，无失败或跳过；每条命令限时60秒。日志路径是本机复核材料，后续可按命令重新生成，不把旧XML合并成一次全量执行。

| 批次 | 实际命令（在应用根目录） | 结果 | 本机日志 |
| --- | --- | --- | --- |
| 陈旧告警与清理 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dit.test=P1MonitoringIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 63单测 + 1综合IT，33.985秒 | `/tmp/agv-p1-core.log` |
| 慢读与重连 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=StreamBackpressureIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 3单测 + 1综合IT，30.535秒 | `/tmp/agv-p1-stream-fixed.log` |
| 持久化回归 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest,AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 11单测 + 1综合IT，43.418秒 | `/tmp/agv-p1-persistence-regression.log` |
| HTTP回归 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=AlarmRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitoringApiIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 3单测 + 1综合IT，24.880秒 | `/tmp/agv-p1-api-regression.log` |
| 并发与TCP回归 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dtest=DeviceStateTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MonitorConcurrencyIT,DeviceClientIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 8单测 + 8IT，32.585秒 | `/tmp/agv-p1-concurrency-regression.log` |
| 配置与启动回归 | `timeout 60s ./mvnw -o -B -ntp -pl backend -am -Dit.test=BackendApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 63单测 + 8IT，30.348秒 | `/tmp/agv-p1-bootstrap-regression.log` |

去重后的后端覆盖为63单元用例和20集成用例；这些集成用例包含多步骤场景，不等同于20条简单断言。模拟器本轮P1未修改，沿用前文21项验证。`-o`适用于已缓存依赖的本机；首次运行移除离线参数。

### B01～B07分层范围

| 编号 | 当前证据 | 判定边界 |
| --- | --- | --- |
| B01 | 最终镜像实际观察1800.32秒，61个样本；线程41～44、连接始终13、队列无积压，单车SILENT时其他车继续采集，报告及清理PASS | 只覆盖3台模拟车，不证明10台满容量或生产SLA |
| B02 | P1MonitoringIT真实MySQL：1001条早于截止时间、1条恰在截止时间；首批删除1000、定时任务删除剩余1，边界行及告警/事件保留；失败计数与再次运行通过 | 执行了实际清理入口；未等待24小时调度间隔 |
| B03 | system-check.py：暂停MySQL后readiness503、REST503、livenessUP；SSE完整旧视图+DEGRADED；持久化/视图错误计数增加，无假DEVICE_OFFLINE；解冻及仅重启数据库后恢复 | 缺失历史不自动回填，不证明任意网络分区或主从切换 |
| B04 | system-check.py：带已确认ACTIVE低电量重启后端后同一ID保持ACTIVE；单独重启模拟器后3台重验并恢复；原P0保留卷/启停属性回归仍通过 | 模拟器重启回默认数据是模拟器语义，不是实际设备状态可随意重置 |
| B05 | MonitoringApiIT验证20连接及第21条503；StreamBackpressureIT用5条512字节接收窗口的真实TCP慢读触发丢帧，采集继续、队列<=40、发送<=20；随后10次重连，每次10帧序号递增，最终连接/队列/发送线程归零 | 慢读测试使用1ms推送填充缓冲；默认1秒推送，未穷举所有网络/关闭交错 |
| B06 | P1MonitoringIT：9秒不触发、10秒DATA_STALE，INVALID/OFFLINE不恢复，GOOD恢复，再冻结新ID，停用SUPPRESSED；真实Compose和浏览器验证相同业务路径 | 重启旧值和仅数据库失败不冒充设备冻结；规则单测覆盖 |
| B07 | README可复跑命令、真实P1桌面/手机截图、完整浏览器流程通过，版本与限制已记录 | 用户独立演示与面试回答仍未进行，不把工程自动化当个人学习通过 |

### 首轮失败与修复

- 首次P1测试编译因SimpleMeterRegistry不实现AutoCloseable失败，改为finally显式close后通过；没有跳过清理。
- 初次慢读测试捕获`Failure in @ExceptionHandler`：SseEmitter尚未绑定HTTP时发送线程已运行，Spring内部早期缓冲增长；修复为MVC异步初始化后才发送。最终真实慢读/重连测试通过，并断言无该错误及执行器关闭错误。
- API时间筛选fixture由固定旧日期改为当前UTC前30秒，避免新增7天清理任务删除验收样本；保留左闭右开、稳定ID分页断言，未放宽生产查询条件。
- 第一轮长时检查在故障阶段通过后，为纳入SSE修复的新镜像而主动中止；`/tmp/agv-p1-system-attempt1.json`为FAIL/KeyboardInterrupt、cleanup=PASS、b01Passed=false，不能算B01通过。随后重建`agv-p1-build-backend/simulator`并启动最终完整检查。

### 浏览器与截图

使用仓库`scripts/browser-check.js`，真实本机Java后端/模拟器与独占MySQL，Chromium无头运行。最终输出需为`result: PASS`；故意登记不允许的host返回400属于预期断言，页面运行时异常应为0。默认Chrome路径不可执行时使用机器已安装Chromium配置，没有新增全局依赖。

截图`docs/evidence/2026-09-14-p1-desktop.png`（1440px）和`2026-09-14-p1-mobile.png`（390px）展示3台模拟车+1条停用测试登记、ONLINE/STALE及低电量/DATA_STALE活动告警。桌面和手机均已实际查看；手机表格内部可横向滚动，整页无横向溢出。截图来自本地Java浏览器环境，不充作Compose截图。

- 最终浏览器脚本日志：`/tmp/agv-p1-browser-final.log`，返回PASS，覆盖11类流程。额外补充等待1号GOOD后再核对恢复，避免以3号刚上线代替1号新样本提交。
- 从最终系统验收的backend容器只读复制JAR，逐字节比较其`BOOT-INF/classes/`与本机最新`backend/target/classes/`：44项完全相同，静态HTML/CSS/JS也与当前源码相同。未用旧镜像代替最终代码验收。

### 最终30分钟系统报告

- 构建：`docker compose --env-file .env.example -p agv-p1-build build`退出0；运行：`python3 scripts/system-check.py --report /tmp/agv-p1-system-final.json`退出0。副本归档为[完整JSON报告](evidence/2026-09-14-p1-system-check.json)，含实际镜像ID、运行环境及全部观察样本，无凭据。
- 随机项目`agv-system-e6b106dd`，2026-09-14 08:37:07～09:09:22 UTC；前置故障演练后实际连续观察1800.32秒，61个约30秒间隔样本。环境为报告中的WSL2 Linux、12个可见CPU、Temurin17.0.20+8。
- 线程41～44，后端已建立TCP连接始终13，轮询队列最大0，堆使用26.84～76.72MiB；VALID读取计数53→5393，增量5340。指标是采样观察，不能证明任意时刻峰值或无限期无泄漏。B05的SSE慢读另行验证，不能把本次SSE连接为0的稳态阶段当作慢读负载。
- 观察第60～120秒只切2号SILENT；1/3号持续ONLINE/GOOD且心跳推进，2号OFFLINE后恢复。最终无活动告警；三台历史总数186/180/186，包含观察前的故障演练记录，不能按180次理想采样直接对齐，也不补齐失联区间。
- 08:57:34与09:06:02 UTC两次只读补充观测：持久化失败24、视图失败6、通信错误75、繁忙跳过166、SSE发送失败5均未增长；均为后端重启后的计数，不能据此声称全程零错误。SSE发送失败包含测试客户端读完首帧关闭的连接。
- `EMPTY_START / STALE_INVALID_RECOVERY / BACKEND_RESTART_ACTIVE / DATABASE_PAUSE_RECOVERY / DATABASE_RESTART / SIMULATOR_RESTART / ENDURANCE`全部PASS；`result=PASS`、`b01Passed=true`、`cleanup=PASS`。测试独占容器和卷已清理，未留下长期运行的演示服务。
- Git差异与文档链接检查通过；原始学习记录保持不变。T01～T19工程DONE，T20仅余个人演示与学习考察；本轮未提交、未推送。
