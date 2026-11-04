CREATE TABLE user_api_policies (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    feature_group VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrent_requests INTEGER,
    updated_by INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_api_policy_user_group UNIQUE (user_id, feature_group),
    CONSTRAINT ck_user_api_policy_feature_group CHECK (feature_group IN (
        'MODEL_ASSET',
        'DATASET_ASSET',
        'TRAINING_DEFINITION',
        'TRAINING_TASK',
        'INFERENCE_TASK',
        'SYSTEM_ADMIN_AUDIT'
    )),
    CONSTRAINT ck_user_api_policy_concurrency CHECK (
        max_concurrent_requests IS NULL OR max_concurrent_requests >= 1
    )
);

-- 账号表由 module1-schema-postgresql.sql 管理，不属于本组 Flyway 迁移。
-- 正式平台已有 users 表时保留外键；只加载业务迁移的隔离测试库也必须能从零构建。
DO $$
BEGIN
    IF to_regclass('public.users') IS NOT NULL THEN
        ALTER TABLE user_api_policies
            ADD CONSTRAINT fk_user_api_policies_user
            FOREIGN KEY (user_id) REFERENCES users(id);
        ALTER TABLE user_api_policies
            ADD CONSTRAINT fk_user_api_policies_updated_by
            FOREIGN KEY (updated_by) REFERENCES users(id);
    END IF;
END
$$;

CREATE INDEX idx_user_api_policies_user_id ON user_api_policies (user_id);

COMMENT ON TABLE user_api_policies IS '用户级 API 功能开关和并发上限；无记录表示继承角色默认权限';
COMMENT ON COLUMN user_api_policies.enabled IS 'false 拒绝该功能组请求；true 允许后继续执行角色和对象权限';
COMMENT ON COLUMN user_api_policies.max_concurrent_requests IS '该用户在该功能组的并发请求上限；NULL 表示不限流';
COMMENT ON COLUMN user_api_policies.version IS '乐观锁版本，防止管理员并发覆盖';
