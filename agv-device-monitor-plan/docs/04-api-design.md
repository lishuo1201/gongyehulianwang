# 04 REST / SSE / 模拟器接口契约

状态：待实现。P0 使用同源页面和后端，API 前缀 `/api/v1`；不添加跨域通配配置。

## 1. 通用格式

成功时直接返回资源对象或分页对象，不再套一层业务 code。错误返回正确 HTTP 状态码和以下结构：

```json
{
  "code": "DEVICE_CODE_EXISTS",
  "message": "设备编码已存在",
  "fieldErrors": [],
  "traceId": "demo-trace-id"
}
```

HTTP：400 参数非法、404 资源不存在、409 唯一冲突/容量限制、503 依赖不可用、500 未分类错误。不能把所有错误改为 HTTP 200。响应不包含堆栈、密码或连接串。

所有 ID 使用字符串；时间使用 ISO 8601 UTC；速度字段单位 m/s；无可信数据用 null。列表固定排序，page 从 1 开始，size 默认 20、最大 100，返回 `items/page/size/total`。

## 2. 设备接口

| 方法 | 路径 | 输入 / 响应 |
| --- | --- | --- |
| GET | `/devices` | 分页列表，可按 enabled 过滤；id ASC |
| POST | `/devices` | 创建；201 + 完整设备对象 |
| GET | `/devices/{id}` | 设备配置；不存在为 404 |
| PATCH | `/devices/{id}` | 仅允许 name、enabled；200 + 更新后的对象 |
| GET | `/devices/{id}/snapshot` | 最新状态；未采集时 UNKNOWN/null |
| GET | `/devices/{id}/history` | from、to、page、size；sampledAt DESC、id DESC |
| GET | `/devices/{id}/events` | from、to、page、size；occurredAt DESC、id DESC |

POST 示例：

```json
{
  "deviceCode": "AGV-001",
  "name": "一号搬运车",
  "host": "simulator",
  "port": 1502,
  "unitId": 1,
  "enabled": true
}
```

字段限制：deviceCode 为 `[A-Z0-9-]{1,32}`；name 去首尾空白后 1～64 字符；host 只接受显式允许列表中的值；port 1～65535；unitId 1～247。body 出现未定义字段返回 400，防止拼错字段被忽略。

默认允许 host=`simulator`，端口 1502；开发配置可显式增加 `127.0.0.1`。不接受 URL、用户信息、路径或任意网络地址，不把设备登记接口变成网络探测入口。

设备响应包含 id、请求字段、configRevision、createdAt、updatedAt。PATCH 空对象或连接字段变更返回 400；重复设置同一个 enabled 值是幂等操作，不重置计时、不重复关闭告警。

历史默认范围是最近 24 小时；from/to 必须同时提供或同时省略，from < to，最大跨度 7 天。查询约定左闭右开 `[from,to)`；空结果为 items=[]，不是 404。

## 3. 快照对象

```json
{
  "deviceId": "1",
  "deviceCode": "AGV-001",
  "connectionStatus": "ONLINE",
  "dataQuality": "GOOD",
  "runState": "RUNNING",
  "batteryPercent": 76,
  "speedMps": 1.2,
  "positionCode": 3,
  "targetCode": 7,
  "faultCode": 0,
  "heartbeat": 123,
  "lastResponseAt": "2026-09-11T08:00:00.000Z",
  "lastFreshAt": "2026-09-11T08:00:00.000Z",
  "snapshotRevision": "15"
}
```

位置在页面格式化为 `P0003`、`P0007`，不是物理坐标或真实 AGV 地图节点。历史 items 包含 id、sampledAt、runState 和业务数值；不添加虚假的当时 connectionStatus。

## 4. 告警接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/alarms` | 按 deviceId、ruleCode、status 筛选，分页，triggeredAt DESC、id DESC |
| POST | `/alarms/{id}/ack` | 无请求体；200 返回对象，保留首次 acknowledgedAt |

告警响应包含 id、deviceId、deviceCode、ruleCode、severity、status、triggerValue、lastValue、triggeredAt、lastObservedAt、acknowledgedAt、recoveredAt、closedAt、closeReason。

确认已恢复/已抑制的历史告警也允许记录知悉，但不会重开告警。未知 id 返回 404。没有“手动恢复告警”的接口，恢复由规则判断。

## 5. SSE

路径：`GET /api/v1/stream`，Content-Type `text/event-stream`。

只定义一个业务事件 `snapshot`。数据为全量当前视图，不是事件日志：

```text
event: snapshot
data: {"streamEpoch":"example-epoch","sequence":1,"generatedAt":"2026-09-11T08:00:00.000Z","collectorStatus":"RUNNING","devices":[],"activeAlarms":[]}

```

实际实现 devices 为上节快照对象数组；activeAlarms 为告警对象数组，最多 100 条，若超过则增加 `alarmsTruncated:true` 并通过 REST 查询完整列表。当前 10 台 / 4 规则范围内不应截断。

- 建连立即发一次全量视图，每秒最多推送一次，正常保活每 15 秒发送 `: heartbeat` 注释和空行。
- `collectorStatus` 为 RUNNING / DEGRADED；数据库写入失败时使用 DEGRADED，不冒充正常更新。
- `generatedAt` 是视图生成时间，不能用来替代每设备 `lastFreshAt`。
- `sequence` 在一个 streamEpoch 内递增；epoch 改变允许从头计数。
- 不承诺 exactly-once、离线缓存或断点补发；重连用新的全量视图恢复。
- 页面建立 EventSource 后以 stream snapshot 为实时状态源；REST 主要查询历史和完整告警。
- 页面断线提示在 `onerror` 立即显示；重连后覆盖设备和活动告警视图。

## 6. 健康检查

| 路径 | 说明 |
| --- | --- |
| `/actuator/health/liveness` | 进程是否存活；不能因设备离线而失败 |
| `/actuator/health/readiness` | 数据库与迁移、采集调度器是否可用 |

显式启用健康分组，并把数据库依赖加入 readiness。模拟器无数据库，readiness 检查 Modbus 监听已启动。不向外暴露环境变量、配置、堆栈等 Actuator 细节。

## 7. 模拟器专用管理 API

这些接口只存在于 simulator 进程，端口 8081，前缀 `/sim/v1`；不作为真实 AGV 能力展示。默认 Compose 不发布此端口，dev override 可映射本机回环。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/units` | 返回 1/2/3 的当前原始寄存器和场景模式 |
| PUT | `/units/{unitId}/scenario` | 原子切换可复现场景 |
| POST | `/reset` | 恢复 3 台设备默认场景，只改模拟器内存 |

PUT 请求：

```json
{ "mode": "LOW_BATTERY" }
```

模式固定为 NORMAL、LOW_BATTERY、FAULT、SILENT、FROZEN、INVALID。语义见 [寄存器表](10-modbus-register-map.md)。unitId 只能为 1、2、3；未知模式或额外字段返回 400。

SILENT 只对所选 Unit ID 停止 Modbus 响应，不停止整个模拟器管理 API；其他 Unit ID 仍正常响应。这样才能验证单设备故障隔离。
