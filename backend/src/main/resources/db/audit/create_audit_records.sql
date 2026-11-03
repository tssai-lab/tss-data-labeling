-- 操作记录表（审计一期）
CREATE TABLE IF NOT EXISTS audit_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER,
    username        VARCHAR(128) NOT NULL,
    operator_role   VARCHAR(64),
    action_type     VARCHAR(32)  NOT NULL,
    object_type     VARCHAR(64)  NOT NULL,
    object_id       VARCHAR(128),
    result          VARCHAR(16)  NOT NULL,
    fail_reason     VARCHAR(512),
    ip_address      VARCHAR(64),
    request_id      VARCHAR(64),
    detail          TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_user_time
    ON audit_records (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_action_time
    ON audit_records (action_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_username_time
    ON audit_records (username, created_at DESC);

COMMENT ON TABLE audit_records IS '用户操作记录（审计一期）';
COMMENT ON COLUMN audit_records.action_type IS 'LOGIN/UPLOAD/DELETE/TRAIN/INFERENCE/PERMISSION_CHANGE';
COMMENT ON COLUMN audit_records.result IS 'SUCCESS/FAILED';
