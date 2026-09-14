// Isolated demo only: 此检查会修改模拟场景、设备名称并登记测试设备，仅在独立验收环境运行。
// Run with playwright-cli run-code: 传入已打开监控页的 page；第二参数可覆盖本机模拟器管理地址。
// Learning evidence：T11/T13/T18/T19，场景经模拟器HTTP切换，断言则等待真实后端采集和SSE更新页面。
// Keep episode semantics：确认不恢复、恢复再触发换ID、INVALID不能恢复DATA_STALE，均同时核对持久化告警。
// Do not grade from screenshots alone：截图只证明某一时刻显示，完整流程必须执行到末尾返回PASS。
async (page, simulatorUrl = 'http://127.0.0.1:8081') => {
    const base = page.url().split('/').slice(0, 3).join('/');
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', entry => {
        if (entry.type() === 'error' && !entry.text().includes('status of 400') && !entry.text().includes('net::ERR_INTERNET_DISCONNECTED'))
            errors.push(entry.text());
    });
    const check = (value, message) => { if (!value) throw new Error(message); };
    const deviceRow = code => page.locator('#device-rows tr').filter({ hasText: code });
    const scenario = async (unit, mode) => {
        const response = await page.request.put(`${simulatorUrl}/sim/v1/units/${unit}/scenario`, { data: { mode }, timeout: 3000 });
        check(response.ok(), `Scenario failed: ${unit} ${mode}`);
    };
    const waitRow = async (code, values, timeout = 6000) => {
        await page.waitForFunction(({ code, values }) => Array.from(document.querySelectorAll('#device-rows tr'))
            .some(row => row.textContent.includes(code) && values.every(value => row.textContent.includes(value))), { code, values }, { timeout });
    };
    const alarms = async () => (await (await page.request.get(`${base}/api/v1/alarms?size=100`)).json()).items;
    const local = date => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    await page.waitForFunction(() => document.querySelector('#online-count').textContent === '3');
    await deviceRow('AGV-001').getByRole('button').click();
    await page.locator('#device-name').fill('<img src=x onerror=alert(1)>');
    await page.getByRole('button', { name: '保存设置' }).click();
    await page.waitForFunction(() => document.querySelector('#device-message').textContent.includes('已保存'));
    check(await page.locator('#device-rows img').count() === 0, 'Device name must render as text');
    check((await deviceRow('AGV-001').textContent()).includes('<img src=x'), 'Literal device name is missing');
    await page.locator('#device-name').fill('一号搬运车');
    await page.getByRole('button', { name: '保存设置' }).click();
    await page.waitForFunction(() => document.querySelector('#device-message').textContent.includes('已保存'));

    await scenario(1, 'LOW_BATTERY');
    await waitRow('AGV-001', ['在线', '有效', '19%']);
    await page.waitForFunction(() => document.querySelector('#alarm-rows').textContent.includes('低电量'));
    const first = (await alarms()).find(alarm => alarm.deviceCode === 'AGV-001' && alarm.ruleCode === 'LOW_BATTERY' && alarm.status === 'ACTIVE');
    check(first, 'Low battery alarm was not persisted');
    await page.locator('#alarm-rows tr').filter({ hasText: 'AGV-001' }).getByRole('button', { name: '确认知悉' }).click();
    await page.waitForFunction(() => document.querySelector('#alarm-rows').textContent.includes('已确认'));
    const acknowledged = (await alarms()).find(alarm => alarm.id === first.id);
    check(acknowledged.status === 'ACTIVE' && acknowledged.acknowledgedAt, 'Acknowledgment incorrectly recovered alarm');
    await scenario(1, 'NORMAL'); await waitRow('AGV-001', ['76%']);
    await page.locator('#alarm-status').selectOption('RECOVERED');
    await page.waitForFunction(() => document.querySelector('#alarm-rows').textContent.includes('已恢复'));
    await scenario(1, 'LOW_BATTERY'); await waitRow('AGV-001', ['19%']);
    await page.locator('#alarm-status').selectOption('ACTIVE');
    await page.waitForFunction(() => document.querySelector('#alarm-rows').textContent.includes('持续中'));
    const second = (await alarms()).find(alarm => alarm.deviceCode === 'AGV-001' && alarm.ruleCode === 'LOW_BATTERY' && alarm.status === 'ACTIVE');
    check(second && second.id !== first.id, 'A new abnormal episode must have a new alarm ID');

    await scenario(1, 'INVALID'); await waitRow('AGV-001', ['在线', '无效', '19%']);
    await scenario(1, 'LOW_BATTERY'); await waitRow('AGV-001', ['在线', '有效', '19%']);
    await scenario(1, 'FROZEN'); await waitRow('AGV-001', ['在线', '陈旧', '19%'], 14000);
    await page.locator('#alarm-rule').selectOption('DATA_STALE');
    await page.waitForFunction(() => document.querySelector('#alarm-rows').textContent.includes('数据陈旧'));
    const stale = (await alarms()).find(a => a.deviceCode === 'AGV-001' && a.ruleCode === 'DATA_STALE' && a.status === 'ACTIVE');
    check(stale, 'Repeated heartbeat must open DATA_STALE');
    await scenario(1, 'INVALID'); await waitRow('AGV-001', ['在线', '无效', '19%']);
    check((await alarms()).some(a => a.id === stale.id && a.status === 'ACTIVE'), 'Invalid data must not recover DATA_STALE');
    await page.locator('#alarm-rule').selectOption('');
    await scenario(2, 'FAULT'); await waitRow('AGV-002', ['在线', '故障']);
    await scenario(3, 'SILENT'); await waitRow('AGV-003', ['离线', '陈旧', '88%'], 15000);
    check((await deviceRow('AGV-002').textContent()).includes('在线'), 'One silent device blocked another');
    check((await alarms()).some(a => a.id === second.id && a.status === 'ACTIVE'), 'Untrusted data recovered a business alarm');
    await scenario(1, 'NORMAL'); await scenario(2, 'NORMAL'); await scenario(3, 'NORMAL');
    await page.waitForFunction(() => document.querySelector('#online-count').textContent === '3');
    await waitRow('AGV-001', ['在线', '有效', '76%']);
    check((await alarms()).some(a => a.id === stale.id && a.status === 'RECOVERED'), 'Fresh good data must recover DATA_STALE');

    await page.locator('#record-from').fill(local(new Date(Date.now() - 3600000)));
    await page.locator('#record-to').fill(local(new Date(Date.now() + 60000)));
    await page.getByRole('button', { name: '查询记录' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#record-rows tr').length > 1);
    await page.getByRole('button', { name: '状态变化', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#record-head').textContent.includes('变化类型'));
    check((await page.locator('#record-rows').textContent()).includes('数据质量'), 'State changes are missing');

    const testCode = 'AGV-UI-' + Date.now().toString(36).toUpperCase();
    const devices = (await (await page.request.get(`${base}/api/v1/devices?size=100`)).json()).items;
    let unit = 4;
    while (devices.some(device => device.unitId === unit)) unit++;
    await page.getByRole('button', { name: '＋ 登记设备' }).click();
    const dialog = page.locator('#register-dialog');
    await dialog.locator('[name=deviceCode]').fill(testCode);
    await dialog.locator('[name=name]').fill('待采集测试车');
    await dialog.locator('[name=host]').fill('unlisted.invalid');
    await dialog.locator('[name=unitId]').fill(String(unit));
    await dialog.locator('[name=enabled]').uncheck();
    await dialog.getByRole('button', { name: '登记设备', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#register-error').textContent.includes('允许列表'));
    await dialog.locator('[name=host]').fill(devices[0].host);
    await dialog.locator('[name=port]').fill(String(devices[0].port));
    await dialog.getByRole('button', { name: '登记设备', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    await waitRow(testCode, ['已停用', '未知', '—']);
    check(await page.locator('#detail-battery').textContent() === '—', 'Unknown battery is not zero');
    check(!await page.locator('#battery-bar').isVisible(), 'Unknown battery must not show a zero progress bar');
    await page.context().setOffline(true);
    try { await page.waitForFunction(() => document.querySelector('#stream-state').textContent.includes('中断'), null, { timeout: 8000 }); }
    finally { await page.context().setOffline(false); }
    await page.waitForFunction(() => document.querySelector('#stream-state').textContent === '实时连接', null, { timeout: 10000 });
    await page.setViewportSize({ width: 390, height: 844 });
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile page has horizontal overflow');
    await page.setViewportSize({ width: 1440, height: 1080 });
    await deviceRow('AGV-001').getByRole('button').click();
    check(errors.length === 0, 'Browser runtime errors: ' + errors.join('; '));
    return { result: 'PASS', checks: ['real SSE', 'safe text', 'acknowledge', 'recover and reopen', 'DATA_STALE filter and recovery', 'invalid and frozen', 'single-device silence', 'history and events', 'register and null', 'disconnect and reconnect', 'mobile layout'] };
}
