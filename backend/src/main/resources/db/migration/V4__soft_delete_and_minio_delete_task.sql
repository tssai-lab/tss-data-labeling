ALTER TABLE model_asset
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE model_asset
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE dataset_asset
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_asset
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_model_asset_deleted
    ON model_asset (deleted);

CREATE INDEX IF NOT EXISTS idx_model_version_deleted
    ON model_version (deleted);

CREATE INDEX IF NOT EXISTS idx_dataset_asset_deleted
    ON dataset_asset (deleted);

CREATE INDEX IF NOT EXISTS idx_dataset_version_deleted
    ON dataset_version (deleted);

CREATE TABLE IF NOT EXISTS minio_delete_task (
    id VARCHAR(64) PRIMARY KEY,
    bucket VARCHAR(255) NOT NULL,
    object_name VARCHAR(1024) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(128),
    owner_user_id INTEGER,
    status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retry_count INTEGER NOT NULL DEFAULT 5,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    last_retry_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_minio_delete_task_status
    ON minio_delete_task (status);

CREATE INDEX IF NOT EXISTS idx_minio_delete_task_object
    ON minio_delete_task (bucket, object_name);

CREATE INDEX IF NOT EXISTS idx_minio_delete_task_source
    ON minio_delete_task (source_type, source_id);

ALTER TABLE minio_delete_task
    ADD CONSTRAINT ck_minio_delete_task_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED'));

ALTER TABLE minio_delete_task
    ADD CONSTRAINT ck_minio_delete_task_retry
        CHECK (retry_count >= 0 AND max_retry_count > 0 AND retry_count <= max_retry_count);
