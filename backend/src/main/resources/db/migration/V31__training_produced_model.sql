ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS produced_model_version_id VARCHAR(64);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_publish_status VARCHAR(32);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_publish_error TEXT;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_published_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_artifact_path VARCHAR(1024);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_artifact_sha256 VARCHAR(64);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS model_artifact_size_bytes BIGINT;

ALTER TABLE training_experiment_version
    DROP CONSTRAINT IF EXISTS ck_training_model_publish_status;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_model_publish_status
        CHECK (
            model_publish_status IS NULL
            OR model_publish_status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')
        );

ALTER TABLE training_experiment_version
    DROP CONSTRAINT IF EXISTS fk_training_produced_model_version;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT fk_training_produced_model_version
        FOREIGN KEY (produced_model_version_id)
        REFERENCES model_version(id)
        ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_training_produced_model_version
    ON training_experiment_version(produced_model_version_id)
    WHERE produced_model_version_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_training_model_publish_status
    ON training_experiment_version(model_publish_status);
