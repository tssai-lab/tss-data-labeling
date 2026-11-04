-- 允许 cancelled 状态（用户取消排队中的任务）。
-- 之前的 ck_training_experiment_status 未包含 cancelled，导致取消排队时
-- cancelGlobalQueueTask / cancelQueueTask 写入 cancelled 被数据库 CHECK 约束拒绝（HTTP 500）。
ALTER TABLE training_experiment_version
    DROP CONSTRAINT IF EXISTS ck_training_experiment_status;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_experiment_status
        CHECK (status IS NULL OR status IN ('pending', 'queued', 'scheduled', 'running', 'success', 'failed', 'stopped', 'cancelled'));
