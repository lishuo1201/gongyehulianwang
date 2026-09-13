# AGV Device Monitor

面向已有 7 年 Java 互联网开发经验、工业领域基础不足的开发者，目标岗位为工业软件 Java 后端。通过这个项目理解设备接入与工业业务协作，计划以真实 Modbus TCP 读取模拟 AGV，在页面展示状态、历史和告警，并用实际证据训练面试讲述。

学习覆盖设备/PLC、工业协议、采集与控制边界、MES/WMS/调度协作及 Web 管理端；工程范围保持只读采集监控。Java 是业务系统的实现技术，各工业系统的职责与数据方向见 [工业系统全景](docs/02-architecture.md)。

## 当前状态

- 已实现 T01 骨架和 T02 数据库迁移、demo 初始化器；运行验证结果见任务交接。
- 已提供：开发计划、协议与接口契约、任务及验收清单；最新证据见 [任务交接](docs/06-tasks.md)。
- 尚未实现：设备采集、业务接口、页面、告警计算和 Compose 部署。
- 恢复会话时先看任务交接中的学习状态与待答问题；工程完成和学习通过分别判定，再进入下一任务。
- 首次使用请阅读 [START_HERE.md](START_HERE.md)。

## 目标效果

同一个页面显示 `AGV-001`、`AGV-002`、`AGV-003` 的连接状态、数据质量、电量、速度、位置、运行状态和故障码。模拟低电量、设备故障和通信中断，能够看到告警产生与恢复，并查询已保存的历史。

本项目是“AGV 场景下的设备采集与监控”，不具备 AGV 运动控制、任务调度或工业安全控制能力。寄存器协议是本项目自定义的模拟协议，不代表某厂商 AGV 的真实接口。

## 固定范围

| 层次 | 实现选择 |
| --- | --- |
| 模拟设备 | 独立 Java 模拟器，1 个 TCP 服务、3 个 Unit ID |
| 设备采集 | Java 17 + Modbus TCP，1 秒轮询，仅读取 |
| 业务后端 | Spring Boot 单实例、分包组织、JDBC 持久化 |
| 存储 | MySQL，快照、10 秒历史采样、离散状态事件和告警 |
| 实时显示 | SSE + 原生 HTML/CSS/JavaScript，同源访问 |
| 本地部署 | Docker Compose：mysql、simulator、backend |

已锁定版本及尚未验证的兼容性边界见 [设计决策](docs/11-decisions.md)。

## 里程碑

| 里程碑 | 预算 | 判定标准 |
| --- | --- | --- |
| M0 基础建立 | 第 1 天 | 构建骨架和数据库迁移可验证 |
| M1 采集闭环 | 第 2～3 天 | 真实协议通信、数据解码、状态和历史持久化 |
| M2 MVP | 第 4～5 天 | 告警、页面、SSE、部署和 P0 验收通过 |
| M3 增强 | 第 6～10 天 | 重启恢复、故障注入、可观测性和演示材料 |

## 启动方式

在本目录执行，使用 JDK 17；首次构建需要访问 Maven 仓库。`verify` 还需要可访问的 Docker：

```bash
./mvnw -version
timeout 60s ./mvnw test
timeout 60s ./mvnw verify
```

`test` 执行不依赖数据库的基础上下文、Modbus 依赖初始化及 demo 配置校验。`verify` 还启动锁定摘要的临时 MySQL，执行迁移、重启保留、唯一/范围约束、并发插入和真实 HTTP 检查，结束后自动清理测试容器；Docker 不可用时失败，不跳过后宣称通过。测试不读取个人数据库地址，容器端口仅绑定回环地址，凭据动态生成。真实 Modbus 通信仍待 T04/T05。

后端从 T02 起必须配置专用本地 MySQL 的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；没有配置会启动失败，不回退到内存数据库。应用启动会自动执行迁移，只应指向本项目库。迁移账号可通过 Spring Flyway 配置独立提供；所有凭据留在本机，不写入 Git。

构建后在两个终端分别启动：

```bash
java -jar backend/target/backend.jar --spring.profiles.active=demo
java -jar simulator/target/simulator.jar
```

默认仅监听回环地址，HTTP 端口分别为 8080、8081；Ctrl+C 停止。可检查进程健康：

```bash
curl --max-time 5 -f http://127.0.0.1:8080/actuator/health
curl --max-time 5 -f http://127.0.0.1:8081/actuator/health
```

后端 `UP` 现在包含数据库连接健康检查，但仍不证明采集器或监控业务就绪；模拟器的 `UP` 仍只有基础应用证据。模拟器尚未监听 Modbus 1502，根路径尚无监控页面。

`demo` profile 首次创建三台设备及 UNKNOWN 快照；没有该 profile 时只迁移结构，不生成演示设备。默认种子连接为 `simulator:1502`、Unit ID 1/2/3。IDE 调试其他地址时同时提供 `--demo.seed-host=127.0.0.1 --demo.allowed-hosts=127.0.0.1`；端口同样要在允许列表中。已有设备的名称、启停、连接和快照不会被初始化覆盖；种子连接与库中不同时记录明确警告并沿用数据库。

首次迁移为 `backend/src/main/resources/db/migration/V1__create_monitor_tables.sql`。已经应用的 V1 不再编辑，后续结构调整新增 V2、V3。Flyway 清库默认禁用，常规启动不删除业务数据。

下面仍是 T12 的**目标命令**，Compose 文件尚未生成：

```bash
docker compose up --build -d
docker compose ps
```

目标页面：`http://localhost:8080`。环境准备、参数和检查命令见 [部署计划](docs/08-deployment.md)。

## 阅读入口

先看工业全景与产线补料案例，再按学习计划进入任务交接。Java 基础按需复习；你负责设计、预测和解释，Codex 编码与运行验证。面试练习贯穿开发，亲手编码为可选练习。

- [需求与验收边界](docs/01-requirements.md)
- [工业系统全景与 Demo 架构](docs/02-architecture.md)
- [分阶段开发与学习计划](docs/05-development-plan.md)
- [可执行任务清单](docs/06-tasks.md)
- [工业软件 Java 后端面试训练](docs/09-interview-guide.md)
- [测试与验收](docs/07-test-plan.md)
- [全部文档导航](START_HERE.md)

## 完成后由 Codex 补充

- 实测 JDK、Maven、Docker、依赖及镜像版本。
- 实际启动和测试命令及执行记录。
- 实际截图、3～5 分钟演示步骤、已知限制。
- 仅基于实际实现和验证结果填写简历描述，不虚构设备数量、性能或企业落地。
