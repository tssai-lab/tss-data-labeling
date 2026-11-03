ALTER TABLE minio_delete_task
    ADD COLUMN IF NOT EXISTS failed_reset_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE minio_delete_task
    ADD CONSTRAINT ck_minio_delete_task_failed_reset_count
        CHECK (failed_reset_count >= 0);
