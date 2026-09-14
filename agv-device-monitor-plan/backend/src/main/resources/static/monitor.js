'use strict';
/**
 * Read committed device facts：T11，原生页面通过REST读取台账/历史/告警，通过SSE接收后端已提交的实时全量视图。
 * Learning route：loadDevices加载配置 → connect更新快照 → renderFleet/renderDetail展示；
 * loadRecords查询历史或事件，loadAlarms/renderAlarms呈现告警生命周期，操作只走已定义的REST入口。
 * 本页面不连接Modbus，也不根据颜色、速度0或HTTP连通自行判断车辆故障与告警恢复。
 * Evidence：scripts/browser-check.js验证真实页面、异常场景、重连、安全文本和手机布局。
 */
const $ = id => document.getElementById(id);
// Keep separate sources：devices来自配置查询，snapshots/activeAlarms来自SSE，alarmRows保存筛选分页结果。
// Do not merge identities by alias：配置和测量按数据库id/deviceId关联，不能通过名称或编码后缀猜Unit ID。
const state = { devices: [], snapshots: [], activeAlarms: [], selected: null, epoch: null, sequence: -1,
    stream: 'connecting', collector: null, records: 'history', recordPage: 1, alarmPage: 1, alarmRows: [], alarmKey: null };
const labels = { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知', DISABLED: '已停用', GOOD: '有效', STALE: '陈旧',
    INVALID: '无效', RUNNING: '运行', IDLE: '空闲', CHARGING: '充电', FAULT: '故障', ACTIVE: '持续中',
    RECOVERED: '已恢复', SUPPRESSED: '已抑制', LOW_BATTERY: '低电量', DEVICE_FAULT: '设备故障', DEVICE_OFFLINE: '设备离线', DATA_STALE: '数据陈旧',
    CONNECTION: '通信状态', DATA_QUALITY: '数据质量', RUN_STATE: '运行状态', FAULT_CODE: '故障码' };
// Reject late HTTP results：三种请求各自递增编号，切车/翻页/换筛选后，先发后到的响应不能覆盖最新选择。
let source, deviceRequest = 0, recordRequest = 0, alarmRequest = 0;
const text = value => value == null ? '—' : String(value);
const time = value => value == null ? '—' : new Date(value).toLocaleString('zh-CN', { hour12: false });
const position = value => value == null ? '—' : 'P' + String(value).padStart(4, '0');
function element(tag, value, className) {
    // Render text safely: 设备名称和接口文本仅写入 textContent，不能作为 HTML 执行。
    const node = document.createElement(tag);
    if (value != null) node.textContent = String(value);
    if (className) node.className = className;
    return node;
}
function badge(value) {
    const tone = ['ONLINE', 'GOOD', 'RECOVERED'].includes(value) ? 'good' : ['OFFLINE', 'FAULT', 'INVALID'].includes(value) ? 'bad'
        : ['STALE', 'ACTIVE'].includes(value) ? 'warn' : 'neutral';
    return element('span', labels[value] || value || '未知', 'badge ' + tone);
}
function cell(value, className) {
    const node = element('td', null, className);
    node.append(value instanceof Node ? value : document.createTextNode(text(value)));
    return node;
}
function emptyRows(target, count, message) {
    const row = element('tr'); const td = cell(message, 'empty'); td.colSpan = count; row.append(td);
    $(target).replaceChildren(row);
}
function replaceRows(target, rows) {
    // Preserve keyboard focus: 实时刷新替换行时，保留键盘用户正在操作的同一个按钮。
    const active = $(target).contains(document.activeElement) ? document.activeElement.dataset.focus : null;
    $(target).replaceChildren(...rows);
    if (active) Array.from($(target).querySelectorAll('button')).find(button => button.dataset.focus === active)?.focus({ preventScroll: true });
}
function message(id, value, success = false) {
    $(id).textContent = value; $(id).classList.toggle('success', success);
}
// Surface failure：HTTP非2xx抛给操作区提示；前端超时只结束本次等待，不能据此断言服务器事务一定未提交。
async function api(path, options = {}) {
    const response = await fetch('/api/v1' + path, { ...options, signal: AbortSignal.timeout(6000),
        headers: { 'Content-Type': 'application/json', ...options.headers } });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || '请求失败，请稍后重试');
    return body;
}
// Two independent links：浏览器SSE连着，不代表后端成功保存数据；collectorStatus降级也要提示旧缓存风险。
function renderConnection() {
    const healthy = state.stream === 'live' && state.collector === 'RUNNING';
    const name = healthy ? '实时连接' : state.stream === 'live' ? '采集服务降级' : state.stream === 'connecting' ? '正在连接' : '实时连接中断';
    $('stream-state').textContent = name; $('stream-state').className = 'badge ' + (healthy ? 'good' : 'warn');
    $('notice').hidden = healthy || state.stream === 'connecting';
    $('notice').textContent = state.stream === 'live' ? '采集或保存服务暂不可用，当前显示最后已提交的数据。请关注最近有效样本时间。'
        : '实时连接已中断，当前显示的数值可能已经过期。正在尝试重新连接。';
}
function renderFleet() {
    $('total-count').textContent = state.devices.length;
    $('online-count').textContent = state.epoch == null ? '—' : state.snapshots.filter(s => s.connectionStatus === 'ONLINE').length;
    $('quality-count').textContent = state.epoch == null ? '—' : state.snapshots.filter(s => s.connectionStatus !== 'DISABLED' && s.dataQuality !== 'GOOD').length;
    $('alarm-count').textContent = state.epoch == null ? '—' : state.activeAlarms.length;
    if (!state.devices.length) { emptyRows('device-rows', 6, '暂无设备，请先登记设备。'); return; }
    replaceRows('device-rows', state.devices.map(device => {
        const snapshot = state.snapshots.find(s => s.deviceId === device.id) || {};
        const row = element('tr', null, device.id === state.selected ? 'selected' : '');
        const nameCell = cell('');
        const button = element('button', device.deviceCode, 'device-link'); button.type = 'button';
        button.dataset.focus = 'device:' + device.id; button.setAttribute('aria-label', '查看 ' + device.deviceCode + ' ' + device.name);
        button.addEventListener('click', () => selectDevice(device.id));
        nameCell.append(button, element('span', device.name, 'cell-sub'));
        row.append(nameCell, cell(badge(snapshot.connectionStatus || (device.enabled ? 'UNKNOWN' : 'DISABLED'))),
            cell(badge(snapshot.dataQuality)), cell(badge(snapshot.runState)),
            cell(snapshot.batteryPercent == null ? '—' : snapshot.batteryPercent + '%', 'mono'),
            cell(snapshot.speedMps == null ? '—' : snapshot.speedMps.toFixed(2), 'mono'));
        return row;
    }));
    renderDetail(false);
}
// Preserve user edits：SSE刷新只更新测量；configure=true时才填充配置表单，避免每秒覆盖正在输入的名称。
function renderDetail(configure) {
    const device = state.devices.find(d => d.id === state.selected);
    if (!device) return;
    const snapshot = state.snapshots.find(s => s.deviceId === device.id) || {};
    $('detail-title').textContent = device.deviceCode;
    $('device-address').textContent = device.host + ':' + device.port + ' / Unit ' + device.unitId;
    $('record-device').textContent = device.deviceCode;
    $('detail-battery').textContent = snapshot.batteryPercent == null ? '—' : snapshot.batteryPercent + '%';
    // Unknown is not zero：未读到值时隐藏电量条；value的0仅是隐藏控件的DOM默认值，不作为测量展示。
    $('battery-bar').hidden = snapshot.batteryPercent == null; $('battery-bar').value = snapshot.batteryPercent ?? 0;
    $('battery-bar').classList.toggle('low', snapshot.batteryPercent != null && snapshot.batteryPercent < 20);
    $('detail-speed').textContent = snapshot.speedMps == null ? '—' : snapshot.speedMps.toFixed(2) + ' m/s';
    $('detail-position').textContent = position(snapshot.positionCode); $('detail-target').textContent = position(snapshot.targetCode);
    $('detail-fault').textContent = text(snapshot.faultCode); $('detail-response').textContent = time(snapshot.lastResponseAt);
    $('detail-fresh').textContent = time(snapshot.lastFreshAt);
    if (configure) { $('device-name').value = device.name; $('device-enabled').checked = device.enabled; $('device-fields').disabled = false; }
}
function selectDevice(id) {
    state.selected = id; state.recordPage = 1; message('device-message', ''); renderFleet(); renderDetail(true); loadRecords();
}
async function loadDevices() {
    const request = ++deviceRequest;
    try {
        const page = await api('/devices?size=100'); if (request !== deviceRequest) return;
        state.devices = page.items;
        const filter = $('alarm-device').value;
        $('alarm-device').replaceChildren(new Option('全部设备', ''), ...state.devices.map(d => new Option(d.deviceCode + ' · ' + d.name, d.id)));
        $('alarm-device').value = filter;
        if (!state.selected && state.devices.length) selectDevice(state.devices[0].id);
        else { renderFleet(); renderDetail(true); }
    } catch (error) { if (request === deviceRequest) { emptyRows('device-rows', 6, '读取失败：' + error.message); message('device-message', error.message); } }
}
function pagination(prefix, page) {
    $(prefix + '-page-info').textContent = '共 ' + page.total + ' 条 · 第 ' + page.page + ' / ' + Math.max(1, Math.ceil(page.total / page.size)) + ' 页';
    $(prefix + '-prev').disabled = page.page <= 1; $(prefix + '-next').disabled = page.page * page.size >= page.total;
}
// Query stored evidence：用户输入是本地时间，发送前转UTC；没有记录就显示缺口，不插值、不补零。
async function loadRecords() {
    if (!state.selected) return;
    const request = ++recordRequest;
    message('record-error', '');
    try {
        const from = new Date($('record-from').value), to = new Date($('record-to').value);
        if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to || to - from > 7 * 86400000)
            throw new Error('请输入开始早于结束、最长 7 天的时间范围');
        const params = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), page: state.recordPage, size: 10 });
        const type = state.records;
        const page = await api('/devices/' + state.selected + '/' + type + '?' + params);
        if (request !== recordRequest) return;
        const headings = type === 'history' ? ['采样时间', '运行状态', '电量', '速度 m/s', '当前位置', '目标位置', '故障码', '心跳']
            : ['发生时间', '变化类型', '原状态', '新状态', '原因'];
        const head = element('tr'); headings.forEach(label => head.append(element('th', label))); $('record-head').replaceChildren(head);
        if (!page.items.length) emptyRows('record-rows', headings.length, '此时间范围内没有记录。未采集到的数据不会自动补齐。');
        else $('record-rows').replaceChildren(...page.items.map(item => {
            const row = element('tr');
            const values = type === 'history' ? [time(item.sampledAt), badge(item.runState), item.batteryPercent + '%', item.speedMps.toFixed(2),
                position(item.positionCode), position(item.targetCode), item.faultCode, item.heartbeat]
                : [time(item.occurredAt), labels[item.eventType] || item.eventType, labels[item.oldValue] || text(item.oldValue),
                    labels[item.newValue] || text(item.newValue), item.reason];
            values.forEach(value => row.append(cell(value))); return row;
        }));
        pagination('record', page);
    } catch (error) { if (request === recordRequest) { message('record-error', error.message); emptyRows('record-rows', 8, '记录查询失败'); } }
}
// Display an open interval：未关闭时显示从触发到现在的时长，关闭后固定到closedAt。
// Observation limit：这是告警记录未关闭的跨度，不证明采样间每一毫秒都观察到了异常。
function duration(start, end) {
    const seconds = Math.max(0, Math.floor(((end ? new Date(end).getTime() : Date.now()) - new Date(start).getTime()) / 1000));
    return seconds < 60 ? seconds + '秒' : seconds < 3600 ? Math.floor(seconds / 60) + '分' + seconds % 60 + '秒'
        : Math.floor(seconds / 3600) + '小时' + Math.floor(seconds % 3600 / 60) + '分';
}
// Preserve the episode ID：SSE仅含活动告警，REST还可查询已恢复/抑制记录，不能用活动列表代替所有历史。
// Acknowledge is not recovery：确认按钮只提交ack，status仍由后端设备规则决定，不在前端乐观改成RECOVERED。
function renderAlarms() {
    if (!state.alarmRows.length) { emptyRows('alarm-rows', 7, '没有符合筛选条件的告警。'); return; }
    replaceRows('alarm-rows', state.alarmRows.map(saved => {
        const active = saved.status === 'ACTIVE' && state.activeAlarms.find(a => a.id === saved.id);
        const item = active ? { ...saved, ...active, acknowledgedAt: saved.acknowledgedAt ?? active.acknowledgedAt } : saved;
        const row = element('tr'), description = cell(element('strong', item.deviceCode));
        description.append(element('span', labels[item.ruleCode] || item.ruleCode, 'cell-sub'));
        const ack = cell('');
        if (item.acknowledgedAt) { ack.append(element('span', '已确认', 'badge neutral'), element('span', time(item.acknowledgedAt), 'cell-sub')); }
        else {
            const button = element('button', '确认知悉', 'text-button'); button.dataset.focus = 'ack:' + item.id;
            button.addEventListener('click', async () => {
                button.disabled = true; message('alarm-error', '');
                try { await api('/alarms/' + item.id + '/ack', { method: 'POST' }); await loadAlarms(); }
                catch (error) { message('alarm-error', error.message); button.disabled = false; }
            }); ack.append(button);
        }
        row.append(description, cell(badge(item.status)), cell(item.lastValue), cell(time(item.triggeredAt)),
            cell(time(item.closedAt)), cell(duration(item.triggeredAt, item.closedAt), 'mono'), ack); return row;
    }));
}
async function loadAlarms() {
    const request = ++alarmRequest; message('alarm-error', '');
    const params = new URLSearchParams({ page: state.alarmPage, size: 10 });
    for (const [id, key] of [['alarm-device', 'deviceId'], ['alarm-rule', 'ruleCode'], ['alarm-status', 'status']])
        if ($(id).value) params.set(key, $(id).value);
    try {
        const page = await api('/alarms?' + params); if (request !== alarmRequest) return;
        state.alarmRows = page.items; renderAlarms(); pagination('alarm', page);
    } catch (error) { if (request === alarmRequest) message('alarm-error', error.message); }
}
/**
 * Reconnect with a full view：先关闭旧EventSource，避免手动重连产生重复订阅；断开期间保留旧数值并显示警告。
 * 同epoch只接受更大sequence，服务重启换epoch后接受新的全量；这些字段不与设备heartbeat比较。
 * 不回放SSE历史：视图覆盖恢复当前展示，历史证据仍从数据库查询。
 */
function connect() {
    source?.close(); state.stream = navigator.onLine ? 'connecting' : 'disconnected'; renderConnection();
    if (!navigator.onLine) return;
    source = new EventSource('/api/v1/stream');
    source.addEventListener('snapshot', event => {
        try {
            const view = JSON.parse(event.data);
            if (!Array.isArray(view.devices) || !Array.isArray(view.activeAlarms) || typeof view.streamEpoch !== 'string' || !Number.isSafeInteger(view.sequence))
                throw new Error('无效的实时数据格式');
            // Reject old frames: 同一进程内只接受递增序号，服务重启后按新 epoch 接受全量视图。
            if (view.streamEpoch === state.epoch && view.sequence <= state.sequence) return;
            state.epoch = view.streamEpoch; state.sequence = view.sequence;
            state.snapshots = view.devices; state.activeAlarms = view.activeAlarms; state.collector = view.collectorStatus;
            state.stream = 'live'; $('generated-at').textContent = time(view.generatedAt); renderConnection(); renderFleet();
            // Refresh membership on change：活动ID或确认时间变化时重查分页；同一活动事件的观察值由SSE覆盖展示。
            const key = JSON.stringify(view.activeAlarms.map(a => [a.id, a.acknowledgedAt]));
            if (key !== state.alarmKey) { state.alarmKey = key; loadAlarms(); } else renderAlarms();
        } catch (error) { state.stream = 'error'; renderConnection(); message('alarm-error', error.message); }
    });
    source.onerror = () => { state.stream = 'disconnected'; renderConnection(); };
}
// Configure monitoring, not the vehicle：启停的是后端采集；不能从这里下发车辆停车、改电量或换Unit ID。
$('device-form').addEventListener('submit', async event => {
    event.preventDefault(); const id = state.selected; if (!id) return;
    $('device-fields').disabled = true; message('device-message', '');
    try {
        await api('/devices/' + id, { method: 'PATCH', body: JSON.stringify({ name: $('device-name').value, enabled: $('device-enabled').checked }) });
        await loadDevices(); message('device-message', '设置已保存，实时状态将在下一次观测更新。', true);
    } catch (error) { message('device-message', error.message); }
    finally { $('device-fields').disabled = false; }
});
$('record-query').addEventListener('submit', event => { event.preventDefault(); state.recordPage = 1; loadRecords(); });
for (const [id, type] of [['history-tab', 'history'], ['events-tab', 'events']]) $(id).addEventListener('click', () => {
    state.records = type; state.recordPage = 1; $('history-tab').setAttribute('aria-pressed', String(type === 'history'));
    $('events-tab').setAttribute('aria-pressed', String(type === 'events')); loadRecords();
});
for (const [prefix, load] of [['record', loadRecords], ['alarm', loadAlarms]]) {
    $(prefix + '-prev').addEventListener('click', () => { state[prefix + 'Page']--; load(); });
    $(prefix + '-next').addEventListener('click', () => { state[prefix + 'Page']++; load(); });
}
$('alarm-query').addEventListener('submit', event => { event.preventDefault(); state.alarmPage = 1; loadAlarms(); });
for (const id of ['alarm-device', 'alarm-rule', 'alarm-status']) $(id).addEventListener('change', () => { state.alarmPage = 1; loadAlarms(); });
$('register-open').addEventListener('click', () => { message('register-error', ''); $('register-dialog').showModal(); });
$('register-close').addEventListener('click', () => $('register-dialog').close());
$('register-form').addEventListener('submit', async event => {
    event.preventDefault(); const button = event.submitter; button.disabled = true; message('register-error', '');
    const values = new FormData(event.target);
    const body = { deviceCode: values.get('deviceCode'), name: values.get('name'), host: values.get('host'),
        port: Number(values.get('port')), unitId: Number(values.get('unitId')), enabled: values.has('enabled') };
    try { const device = await api('/devices', { method: 'POST', body: JSON.stringify(body) }); await loadDevices();
        selectDevice(device.id); $('register-dialog').close(); event.target.reset(); }
    catch (error) { message('register-error', error.message); }
    finally { button.disabled = false; }
});
$('reconnect').addEventListener('click', () => { connect(); loadDevices(); loadAlarms(); });
// Browser connectivity: 浏览器报告离线时立即停止旧连接，不能等待长连接自行报错后才提示。
window.addEventListener('offline', () => { source?.close(); state.stream = 'disconnected'; renderConnection(); });
window.addEventListener('online', connect);
window.addEventListener('pagehide', () => source?.close());
function localInput(date) { return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16); }
$('record-from').value = localInput(new Date(Date.now() - 86400000)); $('record-to').value = localInput(new Date());
loadDevices(); loadAlarms(); connect();
