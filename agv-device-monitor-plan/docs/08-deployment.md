# 08 本地部署与开发环境

状态：容器构建、空卷启动、保留卷重启、开发映射切换、数据库故障恢复及完整30分钟系统观察已通过。Compose下三台设备在线、告警确认/恢复、单车SILENT隔离及烟测通过。浏览器截图使用本地Java进程；不把两种运行方式的证据混写。准确证据见 [验收记录](07-test-plan.md)。

## 1. 环境选择

面向 Windows + WSL2 Ubuntu 的日常环境。默认在 WSL2 内执行项目命令，使用能被该发行版访问的 Docker Engine 或 Docker Desktop WSL 集成；不要混用两套不可见的镜像和容器环境。

两种运行方式：

| 方式 | 需要 | 使用场景 |
| --- | --- | --- |
| 全部 Compose | Docker / Compose；构建容器使用锁定的 Maven/JDK | 演示和端到端验收 |
| 本地 IDE + 依赖容器 | 本地 JDK17、Maven Wrapper；MySQL与模拟器容器 | 后端断点调试 |

先只检查工具是否存在；缺失时告知用户，不擅自安装全局软件、更改代理、防火墙或系统网络。

```bash
java -version
docker version
docker compose version
```

## 2. Compose 拓扑

| 服务 | 容器内部 | 默认宿主机映射 | 持久化 / 说明 |
| --- | --- | --- | --- |
| mysql | 3306 | 不发布 | 独立命名卷；schema `agv_monitor` |
| simulator | Modbus 1502；管理HTTP 8081 | 不发布 | 内存状态；unit 1/2/3；仅demo |
| backend | HTTP/SSE 8080 | `127.0.0.1:8080:8080` | 无本地持久数据，连接 mysql 与 simulator |

容器间使用 `mysql:3306` 与 `simulator:1502`，不是 localhost。模拟器绑定容器内 `0.0.0.0` 以供同网络后端访问，不等于允许对公网发布。

`compose.dev.yaml` 可额外发布 `127.0.0.1:3307:3306`、`127.0.0.1:1502:1502`、`127.0.0.1:8081:8081` 供本机调试。默认 compose.yaml 不包含这些映射。

这套无鉴权 Demo 不适合公开部署。若后续确需共享，应单独设计身份、访问控制、密钥、网络隔离与审计，不能简单把地址改为 0.0.0.0 对公网开放。

## 3. 配置

| 变量 / 属性 | 使用位置 | 约定 |
| --- | --- | --- |
| `MYSQL_DATABASE` | mysql | `agv_monitor` |
| `MYSQL_USER` | mysql | demo应用用户，不用root跑业务 |
| `MYSQL_PASSWORD` | mysql/backend | 本地 .env 提供，不进git |
| `MYSQL_ROOT_PASSWORD` | mysql | 仅本地初始化/测试使用，不进git |
| `SPRING_DATASOURCE_URL` | backend | JDBC指向 `mysql:3306/agv_monitor`；UTC会话配置需验证 |
| `SPRING_DATASOURCE_USERNAME` | backend | 与应用用户一致 |
| `SPRING_DATASOURCE_PASSWORD` | backend | 对应本地密码 |
| `SPRING_PROFILES_ACTIVE` | backend | `demo`；模拟器不依赖该 profile |
| `demo.allowed-hosts` | backend | 默认仅 `simulator`，dev允许明确的回环地址 |
| `demo.allowed-ports` | backend | 默认1502 |
| `demo.seed-host` | backend | Compose为simulator，IDE连接容器时为127.0.0.1 |
| `demo.seed-port` | backend | 1502 |

采集、历史和告警阈值以 [需求参数表](01-requirements.md) 为准。配置只能有一个权威默认值来源，避免代码/YAML/文档各写一套。

复制 `.env.example` 为 `.env`，替换应用用户名及两个密码占位符；`.env` 已被 Git 与构建上下文忽略。不要覆盖已有 `.env`。默认后端端口由 `AGV_HTTP_PORT` 设置，开发映射由 `AGV_MYSQL_PORT/AGV_SIM_HTTP_PORT/AGV_MODBUS_PORT` 设置。不同模式使用独立测试库，避免旧种子host污染当前模式。

## 4. Dockerfile 与健康检查要求

- backend/simulator 分别生成可执行 JAR，固定名称为 `backend.jar`、`simulator.jar`。
- 多阶段构建，编译JDK和运行JRE均与锁定Java主版本一致；非root运行。
- 依赖与镜像锁定具体版本；需要更换时同步版本表。
- MySQL readiness 不能只看容器已经创建；应用在依赖就绪后运行迁移。
- backend readiness 显式检查数据库与调度器；simulator readiness 检查Modbus监听。
- 健康检查命令依赖的工具必须实际存在于镜像，不能假设精简JRE里自带curl。
- 使用命名卷保存MySQL；普通停止与重启不删除卷。

## 5. 启动和检查命令

在项目根目录准备好 `.env` 后：

```bash
docker compose up --build -d
docker compose ps
python3 scripts/smoke-test.py
curl --max-time 5 -f http://localhost:8080/actuator/health/readiness
curl --max-time 5 -f http://localhost:8080/api/v1/devices
curl -N http://localhost:8080/api/v1/stream
```

打开 `http://localhost:8080`。`curl -N` 是持续输出，观察到快照后可手动 Ctrl+C 结束，不应作为无超时阻塞的自动脚本。

停止服务且保留数据：

```bash
docker compose down
```

不要默认执行带 `-v` 的 down，也不要自动 prune、删除个人已有卷或清空数据库。

## 6. 调试模拟器

启用开发端口映射：

```bash
docker compose -f compose.yaml -f compose.dev.yaml up --build -d
```

设置unit 1低电量、恢复正常（只改变模拟器内存）：

也可运行 `scripts/scenario.sh 1 LOW_BATTERY` 和 `scripts/scenario.sh 1 NORMAL`。自定义开发端口时通过 `SIMULATOR_URL=http://127.0.0.1:<端口>` 指定管理地址。该脚本校验 Unit ID 和六种场景，网络失败返回非零。

```bash
curl -X PUT http://localhost:8081/sim/v1/units/1/scenario \
  -H 'Content-Type: application/json' \
  -d '{"mode":"LOW_BATTERY"}'
```

```bash
curl -X PUT http://localhost:8081/sim/v1/units/1/scenario \
  -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL"}'
```

切换所选unit为SILENT可做单设备掉线测试；停止整个simulator则是全部设备掉线场景，两者验收目的不同。

## 7. 测试与烟测

```bash
timeout 60s ./mvnw test
timeout 60s ./mvnw -pl simulator -am verify
```

前者为快速测试；后者执行模拟器真实 TCP/HTTP 验证。后端 MySQL 和 HTTP 集成测试按 README 的类名分批运行，每条命令限制60秒。环境不足时记录 BLOCKED，不把跳过测试说成全量通过。

`python3 scripts/smoke-test.py --base-url http://127.0.0.1:8080` 使用标准库检查 liveness/readiness、三台演示设备、字段类型和首个 SSE 全量事件；流读取有时间上限，失败返回非零。它不修改数据、不清理资源，也不代替车辆状态及告警验收。

## 8. 常见排错顺序

| 症状 | 先检查 | 不要做 |
| --- | --- | --- |
| Windows页面打不开 | WSL中curl、容器端口和HTTP健康检查 | 直接关闭系统防火墙 |
| 后端连不上MySQL | 容器健康、服务名、账号、迁移日志 | 把数据库密码和完整连接信息公开 |
| AGV都UNKNOWN/OFFLINE | simulator监听、port1502、unitId、允许列表、原始响应 | 只增大所有超时掩盖错误 |
| 能连接但数据INVALID | offset、count、字节序、缩放、协议版本 | 强行把非法值写成0 |
| ONLINE但STALE | heartbeat是否推进、场景是否FROZEN | 仅用成功读取次数当设备心跳 |
| Wrapper执行失败 | LF、可执行权限、WSL内JDK、Wrapper文件完整性 | 重置整个仓库覆盖已有修改 |
| SSE不连续 | curl -N、连接关闭日志、代理是否缓冲 | 把页面随机刷新误当推送成功 |

查看指定服务最近日志，不默认输出敏感配置：

```bash
docker compose logs --tail=100 backend
docker compose logs --tail=100 simulator
```

## 9. 指标、历史保留与系统验收

健康和指标仅用于本地诊断。`/actuator/metrics`列出已创建的指标；按需注册的计数器在首次事件前可能尚不存在，404不应解读为值0。

```bash
curl --max-time 5 -f http://127.0.0.1:8080/actuator/metrics/agv.poll.queue.size
curl --max-time 5 -f 'http://127.0.0.1:8080/actuator/metrics/agv.poll.duration?tag=outcome:VALID'
curl --max-time 5 -f http://127.0.0.1:8080/actuator/metrics/agv.sse.connections
```

| 指标 | 解释 |
| --- | --- |
| `agv.poll.duration`，`outcome=VALID/INVALID/PROTOCOL/COMMUNICATION/ERROR/INTERRUPTED` | 整轮网络读取（含适配器重试）的COUNT/TOTAL_TIME/MAX，单位秒；不含SQL耗时 |
| `agv.poll.errors`，`kind=INVALID/PROTOCOL/COMMUNICATION/ERROR` | 按读取结果分类的错误次数 |
| `agv.poll.skipped`，`reason=busy/queue` | 设备上轮未结束或工作队列满而跳过的轮次 |
| `agv.poll.queue.size / workers.active / inflight` | 当前排队、执行中工作线程及在途设备数 |
| `agv.persistence.failures / agv.view.failures` | 业务事务或已提交视图刷新失败次数 |
| `agv.sse.connections / pending / senders.active` | SSE连接、合计待发帧和当前发送线程数 |
| `agv.sse.frames.dropped / failures / closed` | 慢读丢弃旧帧、发送失败及已关闭连接数 |
| `agv.history.deleted / batches / failures` | 清理行数、成功批次数（含空批）及失败轮数 |

计数器读取COUNT，瞬时量读取VALUE；计数随后端重启归零。JVM线程和内存使用Spring Boot已有`jvm.*`指标，未引入外部监控平台。

`history.retention-days`默认7，可用Spring属性配置且必须>=1。应用就绪1秒后首次清理，此后每轮结束间隔24小时；只删除严格早于本轮UTC截止时间的历史采样，单批最多1000行。按日运行意味着正常保留窗口约7～8天；失败有日志和计数，次日重试，不清理告警/事件。

完整隔离系统验收需要Docker、Python3和已构建镜像；不读取个人`.env`。在应用根目录执行：

```bash
docker compose --env-file .env.example -p agv-p1-build build
python3 scripts/system-check.py --report /tmp/agv-system-check.json
```

报告路径必须不存在。构建命令只构建镜像，不用样例凭据启动服务。脚本生成随机Compose项目、私有临时凭据和回环端口，先执行故障演练，再实际观察1800秒；结束只删除自身测试容器和独占数据卷，报告记录cleanup结果。首次构建和故障演练时间不计入30分钟。不要用`timeout 60s`截断该系统观察；Maven测试仍逐条限时60秒。

`--image-prefix`可选另一套本地镜像前缀；`--duration-seconds 60`只做短试跑，报告`b01Passed=false`，不能作为B01验收。运行期间不要对脚本打印的独占测试地址另做手动场景修改；查看JSON的`result=PASS`、`b01Passed=true`及`cleanup=PASS`后才可判定完整通过。

数据库演练使用暂停及重启容器，驱动读超时使失败显式结束；readiness变为不就绪不代表车辆离线。恢复后应用继续采集，缺失历史保持缺口，健康UP本身不证明没有数据缺口。

## 10. 实测记录（T12/T19）

- T01 环境：WSL2 Linux 6.6.87.2；JDK 17.0.20；Wrapper Maven 3.9.11；Docker Engine 29.6.2；Compose v5.3.1。
- T01 骨架依赖已解析，MySQL 镜像只核对了版本/digest；部署、数据库及协议链路未验证。详见设计决策表。
- 从空测试卷启动结果：PASS，三台ONLINE/GOOD、readiness与SSE烟测通过。
- 普通重启保留数据结果：PASS，down不删卷，up后设备ID、名称、enabled、Unit ID和configRevision保持一致；停用设备保持DISABLED。
- 启动与停止命令：已实测up --no-build -d --wait及down；默认只发布后端回环端口，两应用UID/GID为10001。测试独占卷在整轮结束后清理，不改变普通down保留卷的约定。
- Java 构建和运行镜像已按 Dockerfile 中的 SHA-256 摘要拉取；运行镜像内Java实测Temurin-17.0.20+8。构建因 Wrapper 在缺少 unzip 时切换到 tar.gz、与 ZIP 校验值不匹配而失败；已在构建阶段补 unzip，修改后两目标镜像构建通过。
- 原 Maven 3.9.11 ZIP 已完整下载，与 Apache 发布的 SHA-512 一致，SHA-256 与 Wrapper 配置一致；保留原校验配置。

- 首轮切换开发映射时，依赖被重建而旧后端保留，随后PATCH超时；已为两项depends_on加入restart: true，完整重跑通过。此设置处理Compose主动更新依赖，不代表数据库意外宕机或Docker自动重启后的恢复验收；后者仍归P1。

- 后续P1独立验证已完成：数据库暂停/恢复、仅重启数据库、后端带活动告警重启、模拟器重启及1800.32秒持续采集全部PASS；具体注入与证据见[系统报告](evidence/2026-09-14-p1-system-check.json)和验收记录。前条“仍归P1”保留的是P0阶段边界，不表示当前尚未测试。
