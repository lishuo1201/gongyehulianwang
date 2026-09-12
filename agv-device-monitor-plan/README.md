# AGV Device Monitor

面向已有 7 年 Java 互联网开发经验、工业领域基础不足的开发者，目标岗位为工业软件 Java 后端。通过这个项目理解设备接入与工业业务协作，计划以真实 Modbus TCP 读取模拟 AGV，在页面展示状态、历史和告警，并用实际证据训练面试讲述。

学习覆盖设备/PLC、工业协议、采集与控制边界、MES/WMS/调度协作及 Web 管理端；工程范围保持只读采集监控。Java 是业务系统的实现技术，各工业系统的职责与数据方向见 [工业系统全景](docs/02-architecture.md)。

## 当前状态

- 已完成 T01：Java 17 双模块 Maven 骨架、Wrapper、应用入口和冒烟测试。
- 已提供：开发计划、协议与接口契约、任务及验收清单；最新证据见 [任务交接](docs/06-tasks.md)。
- 尚未实现：设备采集、数据库迁移、业务接口、页面、告警和容器部署。
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

T01 已验证以下命令。在本目录执行，使用 JDK 17；首次构建需要访问 Maven 仓库：

```bash
./mvnw -version
timeout 60s ./mvnw test
timeout 60s ./mvnw verify
```

`test` 当前执行上下文及依赖初始化检查；`verify` 还执行两个真实 HTTP 冒烟测试，并生成两个可执行 JAR。当前尚无 Modbus/MySQL 集成用例，后续任务加入后才覆盖这些链路。

构建后在两个终端分别启动：

```bash
java -jar backend/target/backend.jar
java -jar simulator/target/simulator.jar
```

默认仅监听回环地址，HTTP 端口分别为 8080、8081；Ctrl+C 停止。可检查进程健康：

```bash
curl --max-time 5 -f http://127.0.0.1:8080/actuator/health
curl --max-time 5 -f http://127.0.0.1:8081/actuator/health
```

`UP` 当前只证明应用 HTTP 入口工作；没有接入数据库和采集器，不能据此认定完整业务就绪。模拟器尚未监听 Modbus 1502，根路径尚无监控页面。

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
