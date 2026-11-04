ALTER TABLE platform_system_config
    ADD COLUMN IF NOT EXISTS operation_log_max_size INTEGER NULL;

COMMENT ON COLUMN platform_system_config.operation_log_max_size
    IS '每个用户日志存储上限（MB），NULL 表示无限制';
