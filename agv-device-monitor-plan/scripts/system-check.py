#!/usr/bin/env python3
"""Isolated system check：自建Compose测试环境，故障演练后观测30分钟，结束仅清理自身资源。"""
import argparse
import json
import os
import pathlib
import platform
import re
import secrets
import socket
import statistics
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]


# Verify recovery with real dependencies：T14/T16/T19，自建MySQL、模拟器、后端，实际注入故障后观察运行。
# Read in phases：参数与资源隔离 → 空库启动 → 陈旧告警 → 服务/数据库恢复 → 连续采集 → 自有资源清理。
# Separate time budgets：1800秒是稳定性观察窗口，不含启动和前置故障演练；Maven的60秒批次另行执行。
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image-prefix', default='agv-p1-build')
    parser.add_argument('--duration-seconds', type=int, default=1800)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_.-]*', args.image_prefix) or not 60 <= args.duration_seconds <= 7200:
        parser.error('Use a local image prefix and a duration from 60 to 7200 seconds')
    if args.report.exists():
        parser.error('Report already exists; choose a new path')
    project = 'agv-system-' + secrets.token_hex(4)
    report = {'project': project, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'durationSeconds': args.duration_seconds, 'host': platform.platform(), 'cpuCount': os.cpu_count(),
              'stages': [], 'samples': [], 'result': 'RUNNING', 'b01Passed': False}
    with tempfile.TemporaryDirectory(prefix=project + '-') as tmp:
        work = pathlib.Path(tmp)
        # Private credentials: 随机凭据只写临时私有文件，不进入报告或命令参数。
        env = work / 'test.env'
        ports = []
        for _ in range(4):
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                ports.append(listener.getsockname()[1])
        if len(set(ports)) != 4:
            raise RuntimeError('Ephemeral port collision')
        env.write_text('MYSQL_DATABASE=agv_monitor\nMYSQL_USER=u' + secrets.token_hex(4)
                       + '\nMYSQL_PASSWORD=' + secrets.token_hex(24)
                       + '\nMYSQL_ROOT_PASSWORD=' + secrets.token_hex(24) + '\n'
                       + ''.join(f'{key}={value}\n' for key, value in zip(
                           ['AGV_HTTP_PORT', 'AGV_MYSQL_PORT', 'AGV_SIM_HTTP_PORT', 'AGV_MODBUS_PORT'], ports)))
        env.chmod(0o600)
        override = work / 'images.yaml'
        override.write_text(f'services:\n  backend:\n    image: {args.image_prefix}-backend\n'
                            f'  simulator:\n    image: {args.image_prefix}-simulator\n')
        compose = ['docker', 'compose', '--env-file', str(env), '-p', project,
                   '-f', str(ROOT / 'compose.yaml'), '-f', str(ROOT / 'compose.dev.yaml'), '-f', str(override)]
        base = f'http://127.0.0.1:{ports[0]}'
        simulator = f'http://127.0.0.1:{ports[2]}'
        log = open(work / 'runtime.log', 'w+')

        def run(command, capture=False, timeout=60):
            result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE if capture else log,
                                    stderr=log, timeout=timeout)
            if result.returncode:
                raise RuntimeError(f'Command failed: {command[0]} exit={result.returncode}')
            return result.stdout if capture else None

        # Assert expected failures too：断库时预期503同样要检查状态码与JSON，不能把任意异常当故障演练通过。
        def request(path, body=None, method=None, expected=200, target=base):
            req = urllib.request.Request(target + path, data=None if body is None else json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json'}, method=method)
            try:
                response = urllib.request.urlopen(req, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                if response.status not in ((expected,) if isinstance(expected, int) else expected):
                    raise AssertionError(f'{path}: HTTP {response.status}, expected {expected}')
                return json.load(response)

        def until(condition, seconds=40):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                value = condition()
                if value:
                    return value
                time.sleep(.3)
            raise AssertionError('State did not reach expected result')

        def stage(name):
            report['stages'].append(name)
            print(name, flush=True)

        def snapshot(device_id):
            return request('/api/v1/devices/' + device_id + '/snapshot')

        def alarms():
            return request('/api/v1/alarms?size=100')['items']

        def scenario(unit, mode):
            return request(f'/sim/v1/units/{unit}/scenario', {'mode': mode}, 'PUT', target=simulator)

        # Read the right statistic：瞬时量用VALUE，计数器用COUNT；网络轮次耗时的计数不等于成功落库历史条数。
        def metric(name, statistic='VALUE', query=''):
            values = request('/actuator/metrics/' + name + query)['measurements']
            return next(item['value'] for item in values if item['statistic'] == statistic)

        def stream():
            with urllib.request.urlopen(base + '/api/v1/stream', timeout=5) as response:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    line = response.readline(131072)
                    if not line:
                        break
                    if line.startswith(b'data:'):
                        return json.loads(line[5:])
            raise AssertionError('No complete SSE snapshot')

        paused = False
        try:
            print('PROJECT', project, 'BACKEND', base, 'SIMULATOR', simulator, flush=True)
            run(compose + ['up', '--no-build', '-d', '--wait', '--wait-timeout', '120'], timeout=150)
            ids = {d['deviceCode']: d['id'] for d in request('/api/v1/devices?size=100')['items']}
            assert len(ids) == 3
            until(lambda: all(snapshot(id)['connectionStatus'] == 'ONLINE' and snapshot(id)['dataQuality'] == 'GOOD' for id in ids.values()))
            stage('EMPTY_START_PASS')
            first, second, third = [ids['AGV-00' + str(n)] for n in (1, 2, 3)]
            scenario(1, 'FROZEN')
            stale = until(lambda: next((a for a in alarms() if a['deviceId'] == first and a['ruleCode'] == 'DATA_STALE' and a['status'] == 'ACTIVE'), None))
            assert snapshot(first)['connectionStatus'] == 'ONLINE'
            scenario(1, 'INVALID')
            until(lambda: snapshot(first)['dataQuality'] == 'INVALID')
            assert next(a for a in alarms() if a['id'] == stale['id'])['status'] == 'ACTIVE'
            scenario(1, 'NORMAL')
            until(lambda: next(a for a in alarms() if a['id'] == stale['id'])['status'] == 'RECOVERED')
            stage('STALE_INVALID_RECOVERY_PASS')

            scenario(1, 'LOW_BATTERY')
            low = until(lambda: next((a for a in alarms() if a['deviceId'] == first and a['ruleCode'] == 'LOW_BATTERY' and a['status'] == 'ACTIVE'), None))
            request('/api/v1/alarms/' + low['id'] + '/ack', method='POST')
            run(compose + ['restart', 'backend'])
            run(compose + ['up', '--no-build', '-d', '--wait', '--wait-timeout', '90'], timeout=120)
            until(lambda: snapshot(first)['dataQuality'] == 'GOOD')
            assert next(a for a in alarms() if a['id'] == low['id'])['status'] == 'ACTIVE'
            assert len([a for a in alarms() if a['deviceId'] == first and a['ruleCode'] == 'LOW_BATTERY' and a['status'] == 'ACTIVE']) == 1
            stage('BACKEND_RESTART_ACTIVE_PASS')

            # Isolate a storage outage：保留设备通信和HTTP进程，只暂停本次独占MySQL，验证分层降级。
            # Keep committed facts：SSE应保留整份旧视图；设备新值没保存成功时不能成为新的业务展示基准。
            run(compose + ['pause', 'mysql']); paused = True
            # Real database outage: HTTP和设备仍在，验证持久化降级不会被伪装成通信离线。
            time.sleep(12)
            assert request('/actuator/health/liveness')['status'] == 'UP'
            assert request('/actuator/health/readiness', expected=503)['status'] != 'UP'
            assert request('/api/v1/devices', expected=503)['code'] == 'DATABASE_UNAVAILABLE'
            failed = stream()
            assert failed['collectorStatus'] == 'DEGRADED'
            time.sleep(3)
            unchanged = stream()
            assert failed['devices'] == unchanged['devices'] and failed['activeAlarms'] == unchanged['activeAlarms']
            assert metric('agv.persistence.failures', 'COUNT') > 0 and metric('agv.view.failures', 'COUNT') > 0
            run(compose + ['unpause', 'mysql']); paused = False
            until(lambda: request('/actuator/health/readiness', expected=(200, 503))['status'] == 'UP')
            until(lambda: snapshot(first)['dataQuality'] == 'GOOD')
            until(lambda: stream()['collectorStatus'] == 'RUNNING')
            assert not [a for a in alarms() if a['ruleCode'] == 'DEVICE_OFFLINE']
            stage('DATABASE_PAUSE_RECOVERY_PASS')
            mysql = run(compose + ['ps', '-q', 'mysql'], True).strip()
            run(['docker', 'restart', mysql])
            # Wait only for owned dependency readiness: 不重启后端，以验证连接池自身恢复。
            until(lambda: run(['docker', 'inspect', '--format', '{{.State.Health.Status}}', mysql], True).strip() == 'healthy')
            until(lambda: stream()['collectorStatus'] == 'RUNNING')
            stage('DATABASE_RESTART_PASS')
            simulator_id = run(compose + ['ps', '-q', 'simulator'], True).strip()
            run(['docker', 'restart', simulator_id])
            until(lambda: run(['docker', 'inspect', '--format', '{{.State.Health.Status}}', simulator_id], True).strip() == 'healthy')
            until(lambda: all(snapshot(id)['connectionStatus'] == 'ONLINE' and snapshot(id)['dataQuality'] == 'GOOD' for id in ids.values()))
            until(lambda: next(a for a in alarms() if a['id'] == low['id'])['status'] == 'RECOVERED')
            stage('SIMULATOR_RESTART_PASS')

            backend_id = run(compose + ['ps', '-q', 'backend'], True).strip()
            report['images'] = {service: run(['docker', 'inspect', '--format', '{{.Image}}', run(compose + ['ps', '-q', service], True).strip()], True).strip() for service in ['backend', 'simulator', 'mysql']}
            report['java'] = run(compose + ['exec', '-T', 'backend', 'sh', '-c', 'java -version 2>&1'], True).strip()
            # Start the actual observation window：故障步骤结束后才计时；短试跑不会得到b01Passed=true。
            # Scope of proof：观察3台模拟车的有界资源与隔离，不据半小时结果宣称全天候或生产容量。
            start = time.monotonic()
            previous = None
            silent = False
            while True:
                elapsed = time.monotonic() - start
                if args.duration_seconds >= 180 and 60 <= elapsed < 120 and not silent:
                    scenario(2, 'SILENT'); silent = True
                elif elapsed >= 120 and silent:
                    scenario(2, 'NORMAL'); silent = False
                values = {code: snapshot(id) for code, id in ids.items()}
                for code in ['AGV-001', 'AGV-003']:
                    assert values[code]['connectionStatus'] == 'ONLINE' and values[code]['dataQuality'] == 'GOOD'
                    if previous: assert values[code]['heartbeat'] != previous[code]['heartbeat']
                if 80 <= elapsed < 120:
                    assert values['AGV-002']['connectionStatus'] == 'OFFLINE'
                if elapsed >= 150:
                    assert values['AGV-002']['connectionStatus'] == 'ONLINE'
                connections = int(run(['docker', 'exec', backend_id, 'sh', '-c',
                    "awk '$4==\"01\" {n++} END {print n+0}' /proc/net/tcp /proc/net/tcp6"], True))
                sample = {'elapsedSeconds': round(elapsed, 2), 'threads': metric('jvm.threads.live'),
                          'heapBytes': metric('jvm.memory.used', query='?tag=area:heap'), 'connections': connections,
                          'queue': metric('agv.poll.queue.size'), 'inflight': metric('agv.poll.inflight'),
                          'workers': metric('agv.poll.workers.active'), 'sseConnections': metric('agv.sse.connections'),
                          'ssePending': metric('agv.sse.pending'),
                          'validCycles': metric('agv.poll.duration', 'COUNT', '?tag=outcome:VALID')}
                assert sample['queue'] <= 16 and sample['inflight'] <= 3 and sample['workers'] <= 4
                assert sample['connections'] <= 40 and sample['sseConnections'] <= 20 and sample['ssePending'] <= 40
                report['samples'].append(sample)
                print('OBSERVE', json.dumps(sample), flush=True)
                previous = values
                if elapsed >= args.duration_seconds:
                    break
                time.sleep(min(30, args.duration_seconds - elapsed))
            samples = report['samples']
            assert samples[-1]['validCycles'] > samples[0]['validCycles']
            assert statistics.median(s['threads'] for s in samples[-5:]) <= statistics.median(s['threads'] for s in samples[:5]) + 16
            assert statistics.median(s['connections'] for s in samples[-5:]) <= statistics.median(s['connections'] for s in samples[:5]) + 8
            assert not [a for a in alarms() if a['status'] == 'ACTIVE']
            # Count persisted samples：实际采样受间隔、调度和掉线影响，且总数含前置演练；不能要求每秒落一行。
            report['historyCounts'] = {code: request('/api/v1/devices/' + id + '/history?size=1')['total'] for code, id in ids.items()}
            assert all(count >= args.duration_seconds / 10 * .8 for count in report['historyCounts'].values())
            report['result'] = 'PASS'; report['b01Passed'] = args.duration_seconds >= 1800
            stage('ENDURANCE_PASS')
        except BaseException as failure:
            report['result'] = 'FAIL'; report['error'] = type(failure).__name__ + ': ' + str(failure)
            raise
        finally:
            try:
                if paused: run(compose + ['unpause', 'mysql'])
                run(compose + ['logs', '--no-color', '--tail', '150', 'backend'])
            finally:
                try:
                    # Own resources only: 项目名随机且只由本脚本创建，结束仅清理该项目及独占测试卷。
                    run(compose + ['down', '-v', '--remove-orphans'])
                    report['cleanup'] = 'PASS'
                except BaseException:
                    report['cleanup'] = 'FAIL'
                    report['result'] = 'FAIL'
                    raise
                finally:
                    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
                    args.report.parent.mkdir(parents=True, exist_ok=True)
                    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                    if report['result'] != 'PASS':
                        log.seek(0)
                        print(log.read()[-12000:], flush=True)
                    log.close()
                    print('REPORT', str(args.report), report['result'], flush=True)


if __name__ == '__main__':
    main()
