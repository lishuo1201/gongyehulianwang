-- Create schema only: 通用迁移只定义结构，演示设备由 demo 初始化器按运行环境创建。
CREATE TABLE device (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(64) NOT NULL,
    host VARCHAR(128) NOT NULL,
    port INT NOT NULL,
    unit_id INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_device_code UNIQUE (device_code),
    CONSTRAINT uk_device_endpoint UNIQUE (host, port, unit_id),
    CONSTRAINT ck_device_code CHECK (device_code = UPPER(device_code) AND CHAR_LENGTH(TRIM(device_code)) > 0),
    CONSTRAINT ck_device_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_device_host CHECK (CHAR_LENGTH(TRIM(host)) > 0),
    CONSTRAINT ck_device_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_device_unit CHECK (unit_id BETWEEN 1 AND 247),
    CONSTRAINT ck_device_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_device_revision CHECK (config_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE device_snapshot (
    device_id BIGINT NOT NULL PRIMARY KEY,
    connection_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_quality VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    battery_percent SMALLINT NULL,
    speed_mm_s INT NULL,
    position_code INT NULL,
    target_code INT NULL,
    fault_code INT NULL,
    heartbeat INT NULL,
    last_response_at DATETIME(3) NULL,
    last_fresh_at DATETIME(3) NULL,
    snapshot_revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_snapshot_device FOREIGN KEY (device_id) REFERENCES device(id),
    CONSTRAINT ck_snapshot_connection CHECK (connection_status IN ('UNKNOWN', 'ONLINE', 'OFFLINE', 'DISABLED')),
    CONSTRAINT ck_snapshot_quality CHECK (data_quality IN ('UNKNOWN', 'GOOD', 'STALE', 'INVALID')),
    CONSTRAINT ck_snapshot_state CHECK (run_state IN ('UNKNOWN', 'IDLE', 'RUNNING', 'CHARGING', 'FAULT')),
    -- Allow unknown measurements: NULL 保留未知语义，非 NULL 数值仍必须满足范围。
    CONSTRAINT ck_snapshot_battery CHECK (battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_snapshot_speed CHECK (speed_mm_s BETWEEN 0 AND 3000),
    CONSTRAINT ck_snapshot_position CHECK (position_code BETWEEN 0 AND 9999),
    CONSTRAINT ck_snapshot_target CHECK (target_code BETWEEN 0 AND 9999),
    CONSTRAINT ck_snapshot_fault CHECK (fault_code BETWEEN 0 AND 65535),
    CONSTRAINT ck_snapshot_heartbeat CHECK (heartbeat BETWEEN 0 AND 65535),
    CONSTRAINT ck_snapshot_revision CHECK (snapshot_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE device_sample_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sample_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    sampled_at DATETIME(3) NOT NULL,
    run_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    battery_percent SMALLINT NOT NULL,
    speed_mm_s INT NOT NULL,
    position_code INT NOT NULL,
    target_code INT NOT NULL,
    fault_code INT NOT NULL,
    heartbeat INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_history_device FOREIGN KEY (device_id) REFERENCES device(id),
    CONSTRAINT uk_sample_key UNIQUE (sample_key),
    INDEX idx_sample_device_time (device_id, sampled_at, id),
    INDEX idx_sample_time (sampled_at, id),
    CONSTRAINT ck_history_state CHECK (run_state IN ('IDLE', 'RUNNING', 'CHARGING', 'FAULT')),
    CONSTRAINT ck_history_battery CHECK (battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_history_speed CHECK (speed_mm_s BETWEEN 0 AND 3000),
    CONSTRAINT ck_history_position CHECK (position_code BETWEEN 0 AND 9999),
    CONSTRAINT ck_history_target CHECK (target_code BETWEEN 0 AND 9999),
    CONSTRAINT ck_history_fault CHECK (fault_code BETWEEN 0 AND 65535),
    CONSTRAINT ck_history_heartbeat CHECK (heartbeat BETWEEN 0 AND 65535),
    -- Store valid samples only: 故障码和运行状态矛盾的样本不能进入可信历史。
    CONSTRAINT ck_history_fault_state CHECK (
        (run_state = 'FAULT' AND fault_code <> 0) OR (run_state <> 'FAULT' AND fault_code = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE device_state_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    event_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    old_value VARCHAR(32) NULL,
    new_value VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    CONSTRAINT fk_event_device FOREIGN KEY (device_id) REFERENCES device(id),
    INDEX idx_event_device_time (device_id, occurred_at, id),
    CONSTRAINT ck_event_type CHECK (event_type IN ('CONNECTION', 'DATA_QUALITY', 'RUN_STATE', 'FAULT_CODE')),
    CONSTRAINT ck_event_change CHECK (NOT (old_value <=> new_value))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE alarm (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    rule_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    severity VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_slot TINYINT NULL,
    trigger_value VARCHAR(64) NOT NULL,
    `last_value` VARCHAR(64) NOT NULL,
    triggered_at DATETIME(3) NOT NULL,
    last_observed_at DATETIME(3) NOT NULL,
    acknowledged_at DATETIME(3) NULL,
    recovered_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    close_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT fk_alarm_device FOREIGN KEY (device_id) REFERENCES device(id),
    -- One active alarm, many past alarms: 活动槽为 1，非活动槽为 NULL，保留多次历史而限制活动唯一。
    CONSTRAINT uk_alarm_active UNIQUE (device_id, rule_code, active_slot),
    INDEX idx_alarm_device_time (device_id, triggered_at, id),
    INDEX idx_alarm_status_time (status, triggered_at, id),
    CONSTRAINT ck_alarm_rule CHECK (rule_code IN ('LOW_BATTERY', 'DEVICE_FAULT', 'DEVICE_OFFLINE', 'DATA_STALE')),
    CONSTRAINT ck_alarm_severity CHECK (
        (rule_code IN ('LOW_BATTERY', 'DATA_STALE') AND severity = 'WARNING') OR
        (rule_code IN ('DEVICE_FAULT', 'DEVICE_OFFLINE') AND severity = 'CRITICAL')),
    CONSTRAINT ck_alarm_status CHECK (status IN ('ACTIVE', 'RECOVERED', 'SUPPRESSED')),
    -- Reject NULL for ACTIVE explicitly: MySQL CHECK 接受 UNKNOWN，必须显式检查非空以防绕过活动唯一。
    CONSTRAINT ck_alarm_slot CHECK (
        (status = 'ACTIVE' AND active_slot IS NOT NULL AND active_slot = 1) OR
        (status IN ('RECOVERED', 'SUPPRESSED') AND active_slot IS NULL)),
    CONSTRAINT ck_alarm_lifecycle CHECK (
        (status = 'ACTIVE' AND recovered_at IS NULL AND closed_at IS NULL AND close_reason IS NULL) OR
        (status = 'RECOVERED' AND recovered_at IS NOT NULL AND closed_at IS NOT NULL
            AND close_reason IS NOT NULL AND close_reason = 'RULE_RECOVERED' AND closed_at = recovered_at) OR
        (status = 'SUPPRESSED' AND recovered_at IS NULL AND closed_at IS NOT NULL
            AND close_reason IS NOT NULL AND close_reason = 'DEVICE_DISABLED')),
    CONSTRAINT ck_alarm_times CHECK (
        last_observed_at >= triggered_at AND
        (acknowledged_at IS NULL OR acknowledged_at >= triggered_at) AND
        (closed_at IS NULL OR closed_at >= last_observed_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
