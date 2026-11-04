ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS version_no INTEGER;

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS version_label VARCHAR(64);

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS description VARCHAR(2048);

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS change_log TEXT;

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS parent_version_id VARCHAR(64);

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'READY';

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS file_fingerprint VARCHAR(512);

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS created_by INTEGER;

ALTER TABLE dataset_asset
    ADD COLUMN IF NOT EXISTS current_version_id VARCHAR(64);

WITH ordered AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY asset_id
            ORDER BY created_at NULLS LAST, id
        ) AS rn
    FROM dataset_version
)
UPDATE dataset_version dv
SET version_no = ordered.rn
FROM ordered
WHERE dv.id = ordered.id
  AND dv.version_no IS NULL;

UPDATE dataset_version
SET version_label = COALESCE(version, 'v' || version_no)
WHERE version_label IS NULL;

UPDATE dataset_version
SET version = version_label
WHERE version IS NULL OR version = '';

UPDATE dataset_version
SET status = 'READY'
WHERE status IS NULL OR status = '';

UPDATE dataset_version
SET published_at = created_at
WHERE published_at IS NULL;

UPDATE dataset_version
SET created_by = owner_user_id
WHERE created_by IS NULL;

WITH current_versions AS (
    SELECT DISTINCT ON (asset_id)
        asset_id,
        id
    FROM dataset_version
    WHERE deleted = FALSE
    ORDER BY asset_id, version_no DESC, created_at DESC NULLS LAST, id DESC
)
UPDATE dataset_asset da
SET current_version_id = current_versions.id
FROM current_versions
WHERE da.id = current_versions.asset_id
  AND da.current_version_id IS NULL;

ALTER TABLE dataset_version
    ALTER COLUMN version_no SET NOT NULL;

ALTER TABLE dataset_version
    ALTER COLUMN version_label SET NOT NULL;

ALTER TABLE dataset_version
    ALTER COLUMN status SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_version_asset_version_no
    ON dataset_version (asset_id, version_no);

CREATE INDEX IF NOT EXISTS idx_dataset_asset_current_version
    ON dataset_asset (current_version_id);

CREATE INDEX IF NOT EXISTS idx_dataset_version_status
    ON dataset_version (status);

ALTER TABLE dataset_version
    ADD CONSTRAINT fk_dataset_version_parent
        FOREIGN KEY (parent_version_id) REFERENCES dataset_version (id);

ALTER TABLE dataset_asset
    ADD CONSTRAINT fk_dataset_asset_current_version
        FOREIGN KEY (current_version_id) REFERENCES dataset_version (id);

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_status
        CHECK (status IN ('DRAFT', 'READY', 'DEPRECATED', 'ARCHIVED'));

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS version_label VARCHAR(64);

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS version_no INTEGER;

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS version_label_generated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS description VARCHAR(2048);

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS change_log TEXT;

ALTER TABLE dataset_upload_session
    ADD COLUMN IF NOT EXISTS parent_version_id VARCHAR(64);
