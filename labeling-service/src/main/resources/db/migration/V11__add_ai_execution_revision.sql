-- 为 AI 执行增加独立业务版本，供需求方取消操作进行乐观并发控制。
ALTER TABLE ai_execution
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);
