CREATE INDEX IF NOT EXISTS idx_import_job_status_heartbeat
    ON import_job (status, heartbeat_at);

CREATE INDEX IF NOT EXISTS idx_dataset_upload_status_updated
    ON dataset_upload_session (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_dataset_version_deleted_at
    ON dataset_version (deleted, deleted_at);
