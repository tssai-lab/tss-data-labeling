-- Must follow the server-side V31-V40 migration chain. In particular, V32
-- already introduces model_version.artifact_sha256 and its format constraint.
ALTER TABLE model_asset
    ADD COLUMN current_version_id VARCHAR(64);

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS artifact_sha256 VARCHAR(64);

ALTER TABLE model_version
    ADD COLUMN commit_info VARCHAR(1024);

ALTER TABLE model_version
    ADD COLUMN hyper_params JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE model_upload_session
    ADD COLUMN commit_info VARCHAR(1024);

ALTER TABLE model_upload_session
    ADD COLUMN hyper_params JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE model_version
SET status = 'DRAFT',
    published_at = NULL
WHERE status = 'READY'
  AND (
        storage_path IS NULL OR btrim(storage_path) = ''
        OR file_name IS NULL OR btrim(file_name) = ''
        OR size_bytes IS NULL OR size_bytes <= 0
  );

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_ready_artifact_metadata
        CHECK (
            status <> 'READY'
            OR (
                storage_path IS NOT NULL AND btrim(storage_path) <> ''
                AND file_name IS NOT NULL AND btrim(file_name) <> ''
                AND size_bytes IS NOT NULL AND size_bytes > 0
            )
        );

ALTER TABLE model_version
    DROP CONSTRAINT IF EXISTS ck_model_version_artifact_sha256;

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_artifact_sha256
        CHECK (
            artifact_sha256 IS NULL
            OR artifact_sha256 ~ '^[0-9a-f]{64}$'
        );

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_hyper_params_object
        CHECK (jsonb_typeof(hyper_params) = 'object');

ALTER TABLE model_upload_session
    ADD CONSTRAINT ck_model_upload_session_hyper_params_object
        CHECK (jsonb_typeof(hyper_params) = 'object');

ALTER TABLE model_asset
    ADD CONSTRAINT fk_model_asset_current_version
        FOREIGN KEY (current_version_id) REFERENCES model_version (id)
        ON DELETE SET NULL;

CREATE INDEX idx_model_asset_current_version
    ON model_asset (current_version_id);

UPDATE model_asset asset
SET current_version_id = (
    SELECT version.id
    FROM model_version version
    WHERE version.asset_id = asset.id
      AND version.deleted = FALSE
      AND version.status = 'READY'
    ORDER BY version.created_at DESC NULLS LAST, version.id DESC
    LIMIT 1
);
