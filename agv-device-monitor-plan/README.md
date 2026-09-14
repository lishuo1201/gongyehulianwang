# AGV Device Monitor

面向已有 7 年 Java 互联网开发经验、工业领域基础不足的开发者，目标岗位为工业软件 Java 后端。项目以真实 Modbus TCP 读取模拟 AGV，在页面展示状态、历史和告警，并用实际证据训练面试讲述。

学习覆盖设备/PLC、工业协议、采集与控制边界、MES/WMS/调度协作及 Web 管理端；工程范围保持只读采集监控。Java 是业务系统的实现技术，各工业系统的职责与数据方向见 [工业系统全景](docs/02-architecture.md)。

## 当前状态

- P0监控闭环已验收：模拟器、协议、采集、五表持久化、告警、REST/SSE、页面及Compose。
- P1工程已验收：采集/SSE指标、7天历史清理、数据库故障恢复、慢客户端与DATA_STALE告警；完整30分钟稳定性检查通过。准确命令与结果见[测试记录](docs/07-test-plan.md)，原始运行样本见[系统报告](docs/evidence/2026-09-14-p1-system-check.json)。
- 按用户要求先完成全部工程，再开始集中讲解和考察；原答与待答题保留，工程完成不等于学习通过。进度与Git边界见[任务交接](docs/06-tasks.md)。
- 首次使用请阅读 [START_HERE.md](START_HERE.md)。

实际P1浏览器验收截图：[桌面](docs/evidence/2026-09-14-p1-desktop.png) · [手机](docs/evidence/2026-09-14-p1-mobile.png)。展示在线但数据陈旧、低电量与DATA_STALE两条活动告警；包含3台模拟车与1条停用的测试登记，不是厂商设备或生产部署证明。

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
timeout 60s ./mvnw -pl backend -am -Dit.test=BackendApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl backend -am -Dit.test=MonitoringPersistenceIT,DeviceClientIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl backend -am -Dit.test=MonitoringApiIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl backend -am -Dit.test=MonitorConcurrencyIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl backend -am -Dit.test=P1MonitoringIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl backend -am -Dit.test=StreamBackpressureIT -Dfailsafe.failIfNoSpecifiedTests=false verify
timeout 60s ./mvnw -pl simulator -am verify
```

`test` 执行不依赖数据库的上下文、编解码、状态、告警规则、模拟器内存状态及配置校验。集成测试按类分批，避免整个 verify 超过60秒；后端验证启动锁定摘要的临时 MySQL，执行迁移、重启保留、唯一/范围约束、并发插入和真实 HTTP 检查，结束后自动清理测试容器；Docker 不可用时失败，不跳过后宣称通过。测试不读取个人数据库地址，容器端口仅绑定回环地址，凭据动态生成。

P1数据库测试覆盖1000行清理上限、截止时间边界及DATA_STALE生命周期；慢客户端测试使用真实TCP读取背压，并加速测试推送以填满网络缓冲。默认生产演示仍每秒推送一次。机器较慢时继续按测试类拆批，不移除60秒上限或把超时当通过。

只构建和验证模拟器可运行 `timeout 60s ./mvnw -B -ntp -pl simulator -am verify`，不需要数据库或 Docker；包含使用临时回环端口的真实 Modbus TCP 和 HTTP 管理接口检查。后端真实TCP读取证据见 `DeviceClientIT`，完整验收范围以任务交接为准。

后端从 T02 起必须配置专用本地 MySQL 的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；没有配置会启动失败，不回退到内存数据库。应用启动会自动执行迁移，只应指向本项目库。迁移账号可通过 Spring Flyway 配置独立提供；所有凭据留在本机，不写入 Git。

构建后在两个终端分别启动：

```bash
java -jar backend/target/backend.jar --spring.profiles.active=demo \
  --demo.seed-host=127.0.0.1 --demo.allowed-hosts=127.0.0.1
java -jar simulator/target/simulator.jar
```

默认仅监听回环地址，HTTP 端口分别为 8080、8081；模拟器额外监听 Modbus TCP 1502。Ctrl+C 停止并关闭模拟器监听、客户端连接及更新线程。可检查进程健康：

```bash
curl --max-time 5 -f http://127.0.0.1:8080/actuator/health
curl --max-time 5 -f http://127.0.0.1:8081/actuator/health
```

后端 `/actuator/health/readiness` 检查数据库与采集服务，设备离线不等同于进程故障。模拟器启动时绑定 Modbus 端口，绑定失败使启动失败；`UP` 本身不验证每台车的采集行为，真实读验证见测试记录。监控页面位于后端根路径 `http://127.0.0.1:8080`，包含设备详情、历史/事件、告警筛选与确认、登记及启停。

模拟器不依赖后端，可以单独启动。使用标准 Modbus TCP 客户端连接 `127.0.0.1:1502`，分别读取 Unit ID 1/2/3、功能码03、起始地址0、数量8。配置项为 `simulator.modbus.bind-address` 和 `simulator.modbus.port`；端口0用于测试自动分配临时端口。默认只在本机使用，Compose 在容器内监听0.0.0.0，并通过服务名互通。

HTTP 管理接口仅生成模拟场景，不控制真实车辆：

```bash
curl --max-time 5 -f http://127.0.0.1:8081/sim/v1/units
curl --max-time 5 -f -X PUT http://127.0.0.1:8081/sim/v1/units/2/scenario \
  -H 'Content-Type: application/json' -d '{"mode":"SILENT"}'
curl --max-time 5 -f -X PUT http://127.0.0.1:8081/sim/v1/units/2/scenario \
  -H 'Content-Type: application/json' -d '{"mode":"NORMAL"}'
curl --max-time 5 -f -X POST http://127.0.0.1:8081/sim/v1/reset
```

支持 `NORMAL`、`LOW_BATTERY`、`FAULT`、`SILENT`、`FROZEN`、`INVALID`。SILENT 仅抑制所选车的响应，管理接口及其他车继续工作；FROZEN 固定完整有效快照；INVALID 返回电量101。普通场景切换保留心跳，reset 恢复三台默认值并将心跳归零。1号默认电量76%，可验证低电量恢复；2号默认23%，尚未达到25%的恢复阈值。模拟场景由后端的新观测驱动告警；确认仅表示知悉，不能解除实际异常。

`demo` profile 首次创建三台设备及 UNKNOWN 快照；没有该 profile 时只迁移结构，不生成演示设备。默认种子连接为 `simulator:1502`、Unit ID 1/2/3。IDE 调试其他地址时同时提供 `--demo.seed-host=127.0.0.1 --demo.allowed-hosts=127.0.0.1`；端口同样要在允许列表中。种子初始化不覆盖已有设备的名称、启停、连接和业务值；监控运行态在重启时将连接重新置为 UNKNOWN、旧值标为 STALE，并保留展示时间；种子连接与库中不同时记录明确警告并沿用数据库。

首次迁移为 `backend/src/main/resources/db/migration/V1__create_monitor_tables.sql`。已经应用的 V1 不再编辑，后续结构调整新增 V2、V3。Flyway 清库默认禁用，常规启动不删除业务数据。

已提供 Dockerfile、Compose 和 `.env.example`；**容器构建、空卷启动及保留卷重启已实测通过**。先复制 `.env.example` 为 `.env`，填入本机专用凭据，再执行：

```bash
docker compose up --build -d
docker compose ps
python3 scripts/smoke-test.py
```

默认仅发布回环地址8080；使用开发覆盖文件可额外发布模拟器与数据库的回环端口。普通停止使用 `docker compose down` 保留数据卷。环境准备、模拟脚本和验证边界见 [部署指南](docs/08-deployment.md)。

完整故障演练及30分钟稳定性检查使用自建隔离环境，不读取个人`.env`：

```bash
docker compose --env-file .env.example -p agv-p1-build build
python3 scripts/system-check.py --report /tmp/agv-system-check.json
```

构建仅产出镜像；检查生成随机凭据、端口和独占卷，结束清理自身资源，报告路径须不存在。以报告`result=PASS`、`b01Passed=true`、`cleanup=PASS`判定通过；真实1800秒观察之外还需故障演练时间。运行指标及自动清理语义见[部署指南第9节](docs/08-deployment.md#9-指标历史保留与系统验收)。

浏览器场景检查需预装 `playwright-cli` 和可用 Chromium，只在独立验收环境执行（会切换模拟场景、修改测试设备名称，并登记 `AGV-UI-*`）：

```bash
playwright-cli open http://127.0.0.1:8080
playwright-cli run-code "$(cat scripts/browser-check.js)"
playwright-cli close
```

默认模拟器管理地址为 `http://127.0.0.1:8081`。运行返回 `result: PASS` 才表示脚本完成；CLI 的退出码不能代替检查结果。重复执行应使用新的隔离数据环境，避免测试登记耗尽设备容量。

## 阅读入口

全部工程验收后，先看工业全景与产线补料案例，再按任务交接进行集中复盘。Java基础按需复习；你负责预测和解释，Codex结合实际代码与测试讲解，亲手编码为可选练习。

- [需求与验收边界](docs/01-requirements.md)
- [工业系统全景与 Demo 架构](docs/02-architecture.md)
- [分阶段开发与学习计划](docs/05-development-plan.md)
- [可执行任务清单](docs/06-tasks.md)
- [工业软件 Java 后端面试训练](docs/09-interview-guide.md)
- [测试与验收](docs/07-test-plan.md)
- [全部文档导航](START_HERE.md)

## 交付材料与限制

版本及镜像见[设计决策](docs/11-decisions.md)，3～5分钟演示、90秒介绍及集中考察顺序见[面试准备](docs/09-interview-guide.md)。当前只验证单实例和3台模拟AGV；30分钟观察也不能证明大规模容量、生产SLA或真实厂商接入。历史按日清理，正常保留窗口约7～8天，告警和事件不自动删除。对外讲述应区分本人推理与Codex辅助编码/执行的证据。
