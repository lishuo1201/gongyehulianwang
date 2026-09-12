# 08 本地部署与开发环境计划

状态：目标部署规范。T01 已提供 Wrapper 和两个可独立启动的应用骨架，实际本地命令见 README。Dockerfile、Compose 和业务功能尚未实现；下方容器部署命令仍为目标。T12 需要实测并将本文件改成已验证指南。

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

## 2. 目标 Compose 拓扑

| 服务 | 容器内部 | 默认宿主机映射 | 持久化 / 说明 |
| --- | --- | --- | --- |
| mysql | 3306 | 不发布 | 独立命名卷；schema `agv_monitor` |
| simulator | Modbus 1502；管理HTTP 8081 | 不发布 | 内存状态；unit 1/2/3；仅demo |
| backend | HTTP/SSE 8080 | `127.0.0.1:8080:8080` | 无本地持久数据，连接 mysql 与 simulator |

容器间使用 `mysql:3306` 与 `simulator:1502`，不是 localhost。模拟器绑定容器内 `0.0.0.0` 以供同网络后端访问，不等于允许对公网发布。

`compose.dev.yaml` 可额外发布 `127.0.0.1:3307:3306`、`127.0.0.1:1502:1502`、`127.0.0.1:8081:8081` 供本机调试。默认 compose.yaml 不包含这些映射。

这套无鉴权 Demo 不适合公开部署。若后续确需共享，应单独设计身份、访问控制、密钥、网络隔离与审计，不能简单把地址改为 0.0.0.0 对公网开放。

## 3. 目标配置

| 变量 / 属性 | 使用位置 | 约定 |
| --- | --- | --- |
| `MYSQL_DATABASE` | mysql | `agv_monitor` |
| `MYSQL_USER` | mysql | demo应用用户，不用root跑业务 |
| `MYSQL_PASSWORD` | mysql/backend | 本地 .env 提供，不进git |
| `MYSQL_ROOT_PASSWORD` | mysql | 仅本地初始化/测试使用，不进git |
| `SPRING_DATASOURCE_URL` | backend | JDBC指向 `mysql:3306/agv_monitor`；UTC会话配置需验证 |
| `SPRING_DATASOURCE_USERNAME` | backend | 与应用用户一致 |
| `SPRING_DATASOURCE_PASSWORD` | backend | 对应本地密码 |
| `SPRING_PROFILES_ACTIVE` | backend/simulator | `demo`；IDE使用记录清楚的本地配置 |
| `demo.allowed-hosts` | backend | 默认仅 `simulator`，dev允许明确的回环地址 |
| `demo.allowed-ports` | backend | 默认1502 |
| `demo.seed-host` | backend | Compose为simulator，IDE连接容器时为127.0.0.1 |
| `demo.seed-port` | backend | 1502 |

采集、历史和告警阈值以 [需求参数表](01-requirements.md) 为准。配置只能有一个权威默认值来源，避免代码/YAML/文档各写一套。

T12 创建 `.env.example`，值写可识别占位符。用户复制为 `.env` 并填本地测试密码；不把真实密码粘进 README，也不在交接日志打印环境变量。不同模式建议使用独立测试库，避免旧种子host污染当前模式。

## 4. Dockerfile 与健康检查要求

- backend/simulator 分别生成可执行 JAR，固定名称为 `backend.jar`、`simulator.jar`。
- 多阶段构建，编译JDK和运行JRE均与锁定Java主版本一致；非root运行。
- 依赖与镜像锁定具体版本；需要更换时同步版本表。
- MySQL readiness 不能只看容器已经创建；应用在依赖就绪后运行迁移。
- backend readiness 显式检查数据库与调度器；simulator readiness 检查Modbus监听。
- 健康检查命令依赖的工具必须实际存在于镜像，不能假设精简JRE里自带curl。
- 使用命名卷保存MySQL；普通停止与重启不删除卷。

## 5. 目标启动和检查命令

在项目根目录准备好 `.env` 后：

```bash
docker compose up --build -d
docker compose ps
curl -f http://localhost:8080/actuator/health/readiness
curl -f http://localhost:8080/api/v1/devices
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

## 7. 目标测试命令

```bash
./mvnw test
./mvnw verify
```

前者为快速测试；后者由 Failsafe 执行集成测试。T01 当前只有上下文、依赖初始化及 HTTP 冒烟检查；后续加入的 Modbus TCP 和真实 MySQL 集成测试需要相应环境及 Docker。后端验证外层使用 `timeout 60s`；环境不足时记录 BLOCKED，不把跳过测试说成全量通过。

T12/T13 可生成 `scripts/smoke-test.sh`，设置连接和总超时，逐项检查服务、设备数与SSE格式；返回非零表示失败。脚本默认不修改数据、不触发清理。

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

## 9. 实测记录（T12/T19填写）

- T01 环境：WSL2 Linux 6.6.87.2；JDK 17.0.20；Wrapper Maven 3.9.11；Docker Engine 29.6.2；Compose v5.3.1。
- T01 骨架依赖已解析，MySQL 镜像只核对了版本/digest；部署、数据库及协议链路未验证。详见设计决策表。
- 从空测试库启动结果：NOT_RUN。
- 普通重启保留数据结果：NOT_RUN。
- 一键启动与停止命令：待实现后核验。
