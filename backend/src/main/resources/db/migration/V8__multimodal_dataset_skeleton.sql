ALTER TABLE dataset_asset
    DROP CONSTRAINT IF EXISTS ck_dataset_asset_type;

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_type
        CHECK (type IS NULL OR type IN ('CV', 'NLP', 'POINT_CLOUD', 'ROBOT', 'MULTIMODAL'));

ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_task_type;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_task_type
        CHECK (task_type IN ('CV', 'NLP', 'POINT_CLOUD', 'ROBOT', 'MULTIMODAL'));

CREATE TABLE dataset_sample (
    id                  VARCHAR(64)   PRIMARY KEY,
    dataset_version_id  VARCHAR(64)   NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    external_id         VARCHAR(255)  NOT NULL,
    sample_index        INTEGER       NOT NULL CHECK (sample_index >= 0),
    tags                JSONB,
    metadata            JSONB,
    owner_user_id       INTEGER,
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uk_sample_version_external UNIQUE (dataset_version_id, external_id),
    CONSTRAINT uk_sample_version_index UNIQUE (dataset_version_id, sample_index)
);

CREATE INDEX idx_sample_version ON dataset_sample (dataset_version_id);
CREATE INDEX idx_sample_external ON dataset_sample (external_id);
CREATE INDEX idx_sample_tags ON dataset_sample USING GIN (tags);
CREATE INDEX idx_sample_metadata ON dataset_sample USING GIN (metadata);

CREATE TABLE dataset_sample_data (
    id                  VARCHAR(64)   PRIMARY KEY,
    sample_id           VARCHAR(64)   NOT NULL REFERENCES dataset_sample(id) ON DELETE CASCADE,
    dataset_version_id  VARCHAR(64)   NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    data_type           VARCHAR(32)   NOT NULL,
    sensor              VARCHAR(64),
    channel             VARCHAR(32),
    seq                 INTEGER       NOT NULL DEFAULT 0 CHECK (seq >= 0),
    format              VARCHAR(32),
    original_path       VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(255),
    size_bytes          BIGINT        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    checksum            VARCHAR(128),
    content_type        VARCHAR(128),
    zip_entry_offset    BIGINT,
    zip_data_offset     BIGINT,
    compressed_size     BIGINT,
    uncompressed_size   BIGINT,
    compression_method  VARCHAR(16),
    crc32               BIGINT,
    metadata            JSONB,
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,
    CONSTRAINT ck_sample_data_type
        CHECK (data_type IN ('IMAGE', 'TEXT', 'POINT_CLOUD', 'AUDIO', 'VIDEO', 'OTHER'))
);

CREATE UNIQUE INDEX uk_sd_sample_dt_sc_seq_coalesce
    ON dataset_sample_data (
        sample_id,
        data_type,
        COALESCE(sensor, ''),
        COALESCE(channel, ''),
        seq
    );
CREATE INDEX idx_sd_sample ON dataset_sample_data (sample_id);
CREATE INDEX idx_sd_version ON dataset_sample_data (dataset_version_id);
CREATE INDEX idx_sd_dt ON dataset_sample_data (data_type);
CREATE INDEX idx_sd_metadata ON dataset_sample_data USING GIN (metadata);

CREATE TABLE dataset_annotation (
    id                  VARCHAR(64)   PRIMARY KEY,
    sample_id           VARCHAR(64)   NOT NULL REFERENCES dataset_sample(id) ON DELETE CASCADE,
    sample_data_id      VARCHAR(64)   REFERENCES dataset_sample_data(id) ON DELETE CASCADE,
    dataset_version_id  VARCHAR(64)   NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    annotation_type     VARCHAR(64)   NOT NULL,
    format              VARCHAR(32)   NOT NULL,
    original_path       VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(255),
    size_bytes          BIGINT        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    checksum            VARCHAR(128),
    zip_entry_offset    BIGINT,
    zip_data_offset     BIGINT,
    compressed_size     BIGINT,
    uncompressed_size   BIGINT,
    compression_method  VARCHAR(16),
    crc32               BIGINT,
    content_type        VARCHAR(128),
    metadata            JSONB,
    created_at          TIMESTAMPTZ
);

CREATE INDEX idx_anno_sample ON dataset_annotation (sample_id);
CREATE INDEX idx_anno_sd ON dataset_annotation (sample_data_id);
CREATE INDEX idx_anno_version ON dataset_annotation (dataset_version_id);
CREATE INDEX idx_anno_metadata ON dataset_annotation USING GIN (metadata);

CREATE TABLE import_job (
    id                  VARCHAR(64)   PRIMARY KEY,
    dataset_version_id  VARCHAR(64)   NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    status              VARCHAR(32)   NOT NULL,
    progress            INTEGER       NOT NULL DEFAULT 0,
    total_samples       INTEGER,
    imported_samples    INTEGER       NOT NULL DEFAULT 0,
    error_message       TEXT,
    heartbeat_at        TIMESTAMPTZ,
    executor_id         VARCHAR(64),
    owner_user_id       INTEGER,
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    CONSTRAINT uk_import_job_dataset_version UNIQUE (dataset_version_id),
    CONSTRAINT ck_import_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_ij_version ON import_job (dataset_version_id);
CREATE INDEX idx_ij_status ON import_job (status);

ALTER TABLE dataset_upload_session
    ADD COLUMN sample_grouping VARCHAR(32);

ALTER TABLE dataset_upload_session
    ADD COLUMN manifest_path VARCHAR(255);

ALTER TABLE dataset_upload_session
    ADD COLUMN import_job_id VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN asset_created_by_upload BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT fk_dataset_upload_session_import_job
        FOREIGN KEY (import_job_id) REFERENCES import_job(id) ON DELETE SET NULL;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT uk_dataset_upload_session_import_job UNIQUE (import_job_id);

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_sample_grouping
        CHECK (sample_grouping IS NULL OR sample_grouping IN ('MANIFEST'));
