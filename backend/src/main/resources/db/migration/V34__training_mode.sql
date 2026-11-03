ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS training_mode VARCHAR(32);

UPDATE training_experiment_version
SET training_mode = 'FROM_SCRATCH'
WHERE training_mode IS NULL;

ALTER TABLE training_experiment_version
    ALTER COLUMN training_mode SET NOT NULL;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_experiment_mode
        CHECK (training_mode IN (
            'FROM_SCRATCH',
            'FULL_FINETUNE',
            'PEFT',
            'PREFERENCE_OPTIMIZATION'
        ));
