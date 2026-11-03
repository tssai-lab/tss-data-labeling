-- 防止旧快照覆盖任务新状态；历史记录从修订号 0 开始。
ALTER TABLE training_experiment_version ADD COLUMN lock_revision BIGINT NOT NULL DEFAULT 0;

-- 和任务创建同事务保存，只记录摘要与结果编号；保留回执防止删除后的迟到重试重建任务。
CREATE TABLE training_submission (
    request_id VARCHAR(64) PRIMARY KEY,
    request_sha256 VARCHAR(64) NOT NULL,
    training_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_training_owner_created ON training_experiment_version (owner_user_id, created_at DESC, id DESC);
