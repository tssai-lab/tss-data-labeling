ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS mlflow_experiment_id VARCHAR(64);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS mlflow_tracking_uri VARCHAR(512);
