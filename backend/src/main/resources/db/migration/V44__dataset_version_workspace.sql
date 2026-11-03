ALTER TABLE dataset_version
    ADD COLUMN workspace_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE dataset_version
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE dataset_version
SET updated_at = COALESCE(published_at, created_at, NOW())
WHERE updated_at IS NULL;

ALTER TABLE dataset_version
    DROP CONSTRAINT IF EXISTS ck_dataset_version_status;

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_status
        CHECK (status IN ('DRAFT', 'READY', 'DEPRECATED', 'ARCHIVED', 'ABANDONED'));

ALTER TABLE dataset_package
    ADD COLUMN storage_kind VARCHAR(16) NOT NULL DEFAULT 'ZIP';

ALTER TABLE dataset_package
    ADD CONSTRAINT ck_dataset_package_storage_kind
        CHECK (storage_kind IN ('ZIP', 'RAW'));

ALTER TABLE dataset_version_package
    DROP CONSTRAINT IF EXISTS ck_dvp_package_role;

ALTER TABLE dataset_version_package
    ADD CONSTRAINT ck_dvp_package_role
        CHECK (package_role IN ('PRIMARY', 'APPEND', 'OVERLAY'));

ALTER TABLE dataset_sample_data
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_sample_data
    ADD COLUMN deleted_at TIMESTAMPTZ;

UPDATE dataset_sample_data
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE dataset_annotation
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_annotation
    ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE dataset_annotation
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE dataset_annotation
SET updated_at = created_at
WHERE updated_at IS NULL;

DROP INDEX IF EXISTS uk_sd_sample_dt_sc_seq_coalesce;

CREATE UNIQUE INDEX uk_sd_sample_dt_sc_seq_active
    ON dataset_sample_data (
        sample_id,
        data_type,
        COALESCE(sensor, ''),
        COALESCE(channel, ''),
        seq
    )
    WHERE deleted = FALSE;

ALTER TABLE dataset_upload_session
    ADD COLUMN workspace_base_revision BIGINT;

ALTER TABLE dataset_upload_session
    ADD COLUMN target_kind VARCHAR(32);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_operation VARCHAR(16);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_sample_id VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_resource_id VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN expected_sha256 VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN declared_format VARCHAR(32);

ALTER TABLE dataset_upload_session
    ADD COLUMN declared_content_type VARCHAR(128);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_data_type VARCHAR(32);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_sensor VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_channel VARCHAR(32);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_seq INTEGER;

ALTER TABLE dataset_upload_session
    ADD COLUMN target_sample_data_id VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_annotation_type VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN target_metadata JSONB;

ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_purpose;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_purpose
        CHECK (upload_purpose IN (
            'INITIAL_DATASET',
            'APPEND_PACKAGE',
            'WORKSPACE_FILE'
        ));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_target_kind
        CHECK (target_kind IS NULL OR target_kind IN ('DATA', 'ANNOTATION'));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_target_operation
        CHECK (target_operation IS NULL OR target_operation IN ('CREATE', 'REPLACE'));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_target_seq
        CHECK (target_seq IS NULL OR target_seq >= 0);

CREATE INDEX idx_dataset_upload_workspace_active
    ON dataset_upload_session (version_id, status, upload_purpose);

CREATE INDEX idx_dataset_sample_data_active
    ON dataset_sample_data (dataset_version_id, sample_id, deleted);

CREATE INDEX idx_dataset_annotation_active
    ON dataset_annotation (dataset_version_id, sample_id, deleted);
