# 11 设计决策、版本锁定与参考资料

编制日期：2026-09-11；学习目标更新：2026-09-12。这里区分“文档可查”与“本项目已实测”。T01 已完成骨架构建、依赖初始化及 HTTP 冒烟验证，真实设备协议和数据库业务尚未验证；即时进度以 [任务交接](06-tasks.md) 为准。

## 1. 已采用的项目决策

| 编号 | 决策 | 理由 / 边界 |
| --- | --- | --- |
| D01 | Java17单体后端，不拆业务微服务 | 对齐已有Java经验；少量设备下减少基础设施工作 |
| D02 | 独立Java模拟器，真实Modbus TCP | 没有硬件仍能验证协议链路；不冒充真实AGV接口 |
| D03 | 后端与模拟器两个Maven模块 | 一个仓库管理；模拟器可独立启停；不是分布式业务平台 |
| D04 | 原生Web页面+SSE | 只需单向监控，全量小快照易验收；不引入前端构建依赖 |
| D05 | JDBC+MySQL+增量迁移 | 数据和事务规则直接可见；不额外引入ORM学习任务 |
| D06 | 连接/质量/运行三维状态 | 区分通信故障、旧数据和设备业务故障 |
| D07 | 1秒轮询+10秒历史+变化事件 | 实时显示、趋势和审计各有用途；不把不同数据混为一表 |
| D08 | 离线按时间阈值，重试最多一次 | 控制等待、避免短暂故障直接下线；数值均为Demo配置 |
| D09 | 活动告警唯一槽+确认字段 | 去重可跨重启，确认不会误当恢复 |
| D10 | 本机回环访问+目标允许列表 | 无鉴权Demo不公开，避免任意网络连接与设备写控制 |
| D11 | 先P0，再增强 | 验收可复现链路优先；新增范围需要同步计划 |
| D12 | 面向已有 7 年 Java 经验的工业软件后端求职学习 | 补工业系统全景、业务协作和协议语义；完整讲解不扩展只读监控工程范围 |
| D13 | 工业背景先行、阶段学习、面试贯穿 | Java 基础按需复习；用户设计/预测/解释，Codex 编码/验证，亲手编码可选；工程与学习分别验收，T20 汇总 |

这些是为本项目规模做的设计选择，不宣称对所有工业现场最优。

## 2. 版本锁定表

以下版本已由 T01 核对官方资料、解析结果或本地环境。后续更新需要复测；BOM 管理的未来组件并不代表已经加入应用或验证兼容。

| 组件 | 计划基线 / 候选 | 文档核对情况 | 本项目实测状态 |
| --- | --- | --- | --- |
| JDK | Ubuntu OpenJDK `17.0.20+8-1-24.04-Ubuntu` | `java -version`、`javac -version` | 两模块以 `release 17` 编译；字节码 major 61；容器 JDK 尚未选择 |
| Spring Boot | `4.1.1` | 官方要求 Java 17+；父 POM 固定版本 | 两模块上下文、HTTP 和 JAR 启动通过 |
| Spring Framework / Tomcat | `7.0.9` / `11.0.24` | Boot BOM 及测试实际 classpath | 已用于本轮 HTTP 测试 |
| Maven / Wrapper | Maven `3.9.11` / Wrapper `3.3.4` | Apache 官方 only-script 分发；Maven ZIP 校验 SHA-512 | `./mvnw -version`、构建通过；项目记录分发包 SHA-256 |
| Modbus Java 库 | `com.digitalpetri.modbus:modbus-tcp:2.1.6` | Maven Central 坐标与维护者资料一致；EPL-2.0 | 已解析并验证客户端及 TCP 传输对象初始化；互通和超时行为留待 T04/T05 |
| Netty | `4.2.17.Final` | Boot BOM 管理；Modbus 原始声明为 `4.1.136.Final` | 初始化检查通过；尚不构成真实网络兼容性证明，T05 必须复验 |
| JUnit Jupiter | `6.0.3` | Boot BOM 和 Surefire/Failsafe 报告 | T02 完整验证 14 个测试通过，0 失败、0 错误、0 跳过 |
| Compiler / Surefire / Failsafe | `3.15.0` / `3.5.6` / `3.5.6` | 继承固定 Boot 父 POM 的插件版本 | Java 17 编译及 `*Test`、`*IT` 分阶段执行通过 |
| MySQL | 官方镜像 `8.4.10-1.el9`，固定 digest 见下方 | 镜像元数据与临时容器 | T02 五表迁移、重启、约束与并发检查通过 |
| MySQL JDBC / Flyway / Testcontainers | BOM 管理 `9.7.0` / `12.4.0` / `2.0.5` | 已接入后端，JAR 与测试实际 classpath 核对 | T02 真实 MySQL 验证通过；使用 Boot 4 的 starter-flyway 和 flyway-mysql 模块 |
| Docker / Compose | Engine `29.6.2` / Compose `v5.3.1` | CLI 及 `docker info` | 服务可访问；未执行本项目 Compose 部署 |
| 页面 | 浏览器原生 HTML/CSS/JS，无 CDN 必需依赖 | 不需要 Node 构建链 | 未实现，待 T11 |

MySQL 后续测试/部署使用已核对的不可变引用：`mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6`。T02 已用该摘要完成临时数据库验证。Testcontainers 中采用 `mysql@sha256:…` 的等价引用，避免标签与摘要同时出现时的名称解析问题；完整摘要见测试源码。

本机系统 Maven 为 `3.8.7`；本项目以 `./mvnw` 的 `3.9.11` 为准。保留官方 `mvnw`、`mvnw.cmd`，仅统一 LF；本轮验证 WSL/Bash，未执行 Windows CMD。

T01 的 Modbus 检查不绑定端口、不读写寄存器。维护者超时文档说明：连接超时与请求超时分开，响应 promise 超时不保证底层发送或等待被取消；库还可能自行重连。T05 必须针对本版本实测总预算、停止重连和资源释放，不能将初始化通过当作协议验收。不要混用旧版本教程中的包名、Netty 设置或方法签名。

如果使用已有Spring Boot 3.x项目，不为追求版本号主动大升级。先评估能否按现有版本兼容实现，记录选择，再更新本表与构建文件。

## 3. 选型验证门槛

- 能在Java17下解析依赖、编译两个模块并启动测试。
- Modbus候选库必须支持当前需求的客户端、服务端、3个unit、超时、关闭和明确拒绝写操作。
- 对照所用版本验证请求超时与连接生命周期，不把Future取消视为底层操作必然结束。
- 查看传递依赖、许可证和可分发要求；不能仅凭仓库介绍认为可任意再许可。
- 采用版本需要通过T05真实互通测试；失败则记录最小复现和候选替代，不自行大范围换框架。

## 4. 官方/维护者参考资料

以下资料用于关键事实核对，其余阈值、业务协议、工期和数据结构是本项目设计。

1. Spring Boot官方系统要求页：列出4.1.1与Java17最低要求。页面可能随版本更新，T01记录实际采用版本。[System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
2. Modbus库维护者README：提供Java17客户端/服务端说明、Maven坐标和EPL-2.0许可证入口；不是本项目的构建成功证据。[digitalpetri/modbus](https://github.com/digitalpetri/modbus)
3. Modbus官方规范：核对功能码03、编码和寻址，业务映射以本项目协议为准。[Application Protocol V1.1b3](https://www.modbus.org/file/secure/modbusprotocolspecification.pdf)
4. 库维护者的寻址指南：区分Unit ID、功能码、PDU零基地址、显示标签及业务缩放。[Addressing, unit IDs, and data](https://github.com/digitalpetri/modbus/blob/master/docs/user/concepts/addressing-unit-ids-and-data.md)
5. 库维护者的超时指南：明确连接和请求等待的区别，提醒超时不必然取消底层发送；本项目只读，也必须释放资源。[Configure timeouts and reconnection](https://github.com/digitalpetri/modbus/blob/master/docs/user/how-to/operations/configure-timeouts-and-reconnection.md)
6. Spring MVC官方异步文档：SseEmitter及持续写入/断开处理依据，Demo的全量推送和队列大小为自定义设计。[Asynchronous Requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
7. Apache Maven 版本与 Wrapper 机制：[Maven 3.9.11](https://maven.apache.org/docs/3.9.11/release-notes.html)、[Maven Wrapper](https://maven.apache.org/tools/wrapper/)。T01 从 Maven Central 的 `maven-wrapper-distribution:3.3.4:only-script` 提取脚本，未修改全局 Maven 安装。

工业全景新增的 MES/WMS、OPC UA、MQTT 与 VDA 5050 资料在 [架构文档](02-architecture.md)对应说明处引用一手来源。厂商方案用于理解职责，不作为所有工厂的固定拓扑；这些资料不是本项目已实现相关协议或业务系统的证据。

## 5. 变更记录

| 日期 | 变更 | 影响 |
| --- | --- | --- |
| 2026-09-11 | 形成1.0计划包，固定SSE/原生页面/Java模拟器，补协议及状态边界 | 全部开发任务TODO；与原先“可选技术”的提纲相比，本版明确实施默认值 |
| 2026-09-11 | T01：锁定 Boot 4.1.1、Modbus 2.1.6、Maven 3.9.11，创建两模块及冒烟检查 | 5 个测试和独立 JAR 启动通过；未引入 JDBC/Flyway，数据库与真实协议验收仍待后续任务 |
| 2026-09-12 | 按用户确认的 7 年 Java 经验及工业软件后端目标调整教学：补全景与补料案例，按阶段学习，面试贯穿 | 修改现有入口、规则、需求、架构、学习、交接及面试文档；保留只读监控范围和原任务状态。无技术版本、API、数据库或协议变更，无数据迁移；文档检查见本轮交接 |
| 2026-09-13 | T02：五表 V1、JDBC/Flyway、demo 幂等初始化及隔离 MySQL 测试 | 按用户确认保留已有连接与业务修改；后端启动需要数据库，凭据外部提供。14 个测试通过，临时容器已清理；需要执行初始迁移，不包含已有个人库迁移 |

后续记录格式：日期、关联任务、旧决策、新决策、原因、受影响文档/代码、验证结果、是否需要数据迁移。技术基线和协议改动必须能追溯。

T02 参考：[Spring Boot 数据库初始化](https://docs.spring.io/spring-boot/how-to/data-initialization.html)、[Testcontainers 数据库容器](https://java.testcontainers.org/modules/databases/)。依赖使用现有 Boot BOM，不因文档更新自动升级。实际运行结论以 docs/07-test-plan.md 的测试证据为准。
