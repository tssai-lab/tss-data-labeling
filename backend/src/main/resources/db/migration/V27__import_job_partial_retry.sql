ALTER TABLE import_job
    DROP CONSTRAINT IF EXISTS ck_import_job_status;

ALTER TABLE import_job
    ADD CONSTRAINT ck_import_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL', 'SUPERSEDED'));

CREATE TABLE import_job_sample_failure (
    id                  VARCHAR(64)   PRIMARY KEY,
    import_job_id       VARCHAR(64)   NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    dataset_version_id  VARCHAR(64)   NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    package_id          VARCHAR(64)   REFERENCES dataset_package(id),
    external_id         VARCHAR(255)  NOT NULL,
    sample_index        INTEGER       NOT NULL CHECK (sample_index >= 0),
    status              VARCHAR(32)   NOT NULL,
    error_code          VARCHAR(64),
    error_message       TEXT,
    error_details_json  TEXT,
    attempt_count       INTEGER       NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    first_failed_at     TIMESTAMPTZ   NOT NULL,
    last_failed_at      TIMESTAMPTZ   NOT NULL,
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_ijsf_status CHECK (status IN ('FAILED', 'RETRYING', 'RESOLVED')),
    CONSTRAINT uk_ijsf_job_external UNIQUE (import_job_id, external_id)
);

CREATE INDEX idx_ijsf_job_status
    ON import_job_sample_failure (import_job_id, status);

CREATE INDEX idx_ijsf_version_package_status
    ON import_job_sample_failure (dataset_version_id, package_id, status);
