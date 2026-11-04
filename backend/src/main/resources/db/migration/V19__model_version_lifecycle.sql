ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS description VARCHAR(2048);

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS change_log TEXT;

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'READY';

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS created_by INTEGER;

UPDATE model_version
SET status = 'READY'
WHERE status IS NULL OR status = '';

UPDATE model_version
SET published_at = created_at
WHERE published_at IS NULL;

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_status
        CHECK (status IN ('DRAFT', 'READY', 'DEPRECATED', 'ARCHIVED'));

CREATE INDEX IF NOT EXISTS idx_model_version_status
    ON model_version (status);
