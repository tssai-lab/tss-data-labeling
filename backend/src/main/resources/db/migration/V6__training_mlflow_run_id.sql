ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(128);
