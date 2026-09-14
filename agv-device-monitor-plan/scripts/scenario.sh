#!/usr/bin/env bash
set -euo pipefail
# Simulator only: 仅切换本机模拟器场景，不向真实设备写寄存器。
# Learning demo：T13/T19，参数1是模拟器内部Unit ID，不是数据库device_id或设备名称。
# Observe through the backend：此请求成功只证明场景已切换；告警还要等待真实Modbus采集及事务提交。
# Fail on HTTP errors：严格模式和curl的失败退出码阻止脚本在请求失败后继续报告演示成功。
if [[ $# != 2 ]]; then
    echo 'Usage: scenario.sh <1|2|3> <NORMAL|LOW_BATTERY|FAULT|SILENT|FROZEN|INVALID>' >&2
    exit 2
fi
case "$1" in 1|2|3) ;; *) echo 'Unit ID must be 1, 2 or 3' >&2; exit 2;; esac
case "$2" in NORMAL|LOW_BATTERY|FAULT|SILENT|FROZEN|INVALID) ;; *) echo 'Unsupported scenario' >&2; exit 2;; esac
curl --fail-with-body --silent --show-error --connect-timeout 2 --max-time 5 \
    -X PUT "${SIMULATOR_URL:-http://127.0.0.1:8081}/sim/v1/units/$1/scenario" \
    -H 'Content-Type: application/json' --data "{\"mode\":\"$2\"}"
printf '\n'
