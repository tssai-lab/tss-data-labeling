CREATE TABLE dataset_package (
    id                  VARCHAR(64)   PRIMARY KEY,
    dataset_asset_id    VARCHAR(64)   NOT NULL REFERENCES dataset_asset(id),
    storage_path        VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(255)  NOT NULL,
    size_bytes          BIGINT        NOT NULL CHECK (size_bytes >= 0),
    checksum            VARCHAR(128),
    manifest_path       VARCHAR(255),
    status              VARCHAR(32)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uk_dataset_package_storage_path UNIQUE (storage_path)
);

CREATE INDEX idx_dataset_package_asset
    ON dataset_package (dataset_asset_id);

CREATE INDEX idx_dataset_package_deleted
    ON dataset_package (deleted);

CREATE TABLE dataset_version_package (
    dataset_version_id  VARCHAR(64)  NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    package_id          VARCHAR(64)  NOT NULL REFERENCES dataset_package(id),
    package_role        VARCHAR(16)  NOT NULL,
    package_order       INTEGER      NOT NULL CHECK (package_order >= 0),
    created_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (dataset_version_id, package_id),
    CONSTRAINT uk_dvp_version_order UNIQUE (dataset_version_id, package_order),
    CONSTRAINT ck_dvp_package_role CHECK (package_role IN ('PRIMARY', 'APPEND'))
);

CREATE INDEX idx_dvp_version
    ON dataset_version_package (dataset_version_id);

CREATE INDEX idx_dvp_package
    ON dataset_version_package (package_id);

ALTER TABLE dataset_sample
    ADD COLUMN created_by_package_id VARCHAR(64);

ALTER TABLE dataset_sample
    ADD CONSTRAINT fk_dataset_sample_created_package
        FOREIGN KEY (created_by_package_id) REFERENCES dataset_package(id);

ALTER TABLE dataset_sample_data
    ADD COLUMN package_id VARCHAR(64);

ALTER TABLE dataset_sample_data
    ADD CONSTRAINT fk_dataset_sample_data_package
        FOREIGN KEY (package_id) REFERENCES dataset_package(id);

ALTER TABLE dataset_annotation
    ADD COLUMN package_id VARCHAR(64);

ALTER TABLE dataset_annotation
    ADD CONSTRAINT fk_dataset_annotation_package
        FOREIGN KEY (package_id) REFERENCES dataset_package(id);

ALTER TABLE import_job
    DROP CONSTRAINT IF EXISTS uk_import_job_dataset_version;

ALTER TABLE import_job
    ADD COLUMN package_id VARCHAR(64);

ALTER TABLE import_job
    ADD CONSTRAINT fk_import_job_package
        FOREIGN KEY (package_id) REFERENCES dataset_package(id);

CREATE INDEX idx_import_job_package
    ON import_job (package_id);

CREATE UNIQUE INDEX uk_import_job_version_package
    ON import_job (dataset_version_id, package_id)
    WHERE package_id IS NOT NULL;

CREATE UNIQUE INDEX uk_import_job_legacy_version
    ON import_job (dataset_version_id)
    WHERE package_id IS NULL;
