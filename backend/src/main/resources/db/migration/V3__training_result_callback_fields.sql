ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS progress INTEGER;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS metrics_json TEXT;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS log_path VARCHAR(1024);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS output_path VARCHAR(1024);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS error_message TEXT;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP WITH TIME ZONE;

UPDATE training_experiment_version
SET progress = CASE
    WHEN status = 'success' THEN 100
    WHEN status = 'running' THEN 50
    ELSE 0
END
WHERE progress IS NULL;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_experiment_progress
        CHECK (progress IS NULL OR (progress >= 0 AND progress <= 100));

