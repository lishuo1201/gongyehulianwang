#!/usr/bin/env python3
"""Read-only smoke test：检查本机演示服务，不修改设备、告警或数据库。"""
import argparse
import json
import sys
import time
import urllib.request
import uuid


# Check the public chain：T12/T13，按健康→台账→SSE检查一次可观察链路，只读且不清理运行环境。
# Keep evidence narrow：PASS不代表所有车辆均ONLINE/GOOD，也不代替低电量/冻结/掉线的场景验收。
def check(base):
    def get(path):
        with urllib.request.urlopen(base + path, timeout=5) as response:
            body = response.read(1_048_577)
            if len(body) > 1_048_576:
                raise ValueError('Response exceeds demo size limit')
            return json.loads(body)

    # Separate alive and ready：进程可存活但数据库不可用，两个端点均检查，不能只凭端口能连接宣布服务可用。
    for endpoint in ('liveness', 'readiness'):
        if get('/actuator/health/' + endpoint)['status'] != 'UP':
            raise ValueError(endpoint + ' is not UP')
    devices = get('/api/v1/devices?size=100')['items']
    if not {'AGV-001', 'AGV-002', 'AGV-003'} <= {device['deviceCode'] for device in devices}:
        raise ValueError('Three demo devices are missing')
    # Test the JSON contract：资源ID为字符串、Unit ID为整数、enabled为布尔；不能靠Python的隐式类型兼容放宽。
    for device in devices:
        if not isinstance(device['id'], str) or type(device['unitId']) is not int:
            raise ValueError('Invalid resource ID or Unit ID type')
        if type(device['enabled']) is not bool:
            raise ValueError('enabled must be boolean')
    # Bound the stream：读取首个全量事件即关闭，不能把持续SSE当作无超时脚本。
    deadline = time.monotonic() + 8
    with urllib.request.urlopen(base + '/api/v1/stream', timeout=5) as response:
        while time.monotonic() < deadline:
            line = response.readline(131072)
            if not line:
                raise ValueError('SSE ended before snapshot')
            if line.startswith(b'data:'):
                view = json.loads(line[5:])
                uuid.UUID(view['streamEpoch'])
                if type(view['sequence']) is not int or view['sequence'] <= 0 or view['collectorStatus'] != 'RUNNING':
                    raise ValueError('Collector is degraded or sequence is invalid')
                if {item['deviceId'] for item in view['devices']} != {item['id'] for item in devices}:
                    raise ValueError('SSE and registry device sets differ')
                return {'result': 'PASS', 'devices': len(devices), 'activeAlarms': len(view['activeAlarms'])}
    raise ValueError('SSE snapshot deadline exceeded')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.base_url.rstrip('/'))))
    except Exception as error:
        # Report failure：依赖不可用或契约不符合时明确非零退出，不能输出假成功。
        print('Smoke check failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
