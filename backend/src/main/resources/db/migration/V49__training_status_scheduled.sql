ALTER TABLE training_experiment_version
    DROP CONSTRAINT IF EXISTS ck_training_experiment_status;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_experiment_status
        CHECK (status IS NULL OR status IN ('pending', 'queued', 'scheduled', 'running', 'success', 'failed', 'stopped'));
