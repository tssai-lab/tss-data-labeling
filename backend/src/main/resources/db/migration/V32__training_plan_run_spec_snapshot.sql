ALTER TABLE model_version
    ADD COLUMN IF NOT EXISTS artifact_sha256 VARCHAR(64);

ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS artifact_sha256 VARCHAR(64);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS training_plan_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS training_plan_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS resource_profile_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS run_spec_json TEXT,
    ADD COLUMN IF NOT EXISTS run_spec_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS input_model_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS input_dataset_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS input_code_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS code_approval_record_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS runtime_image VARCHAR(512),
    ADD COLUMN IF NOT EXISTS runtime_image_digest VARCHAR(71);

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_artifact_sha256
        CHECK (artifact_sha256 IS NULL OR artifact_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_artifact_sha256
        CHECK (artifact_sha256 IS NULL OR artifact_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE training_experiment_version
    ADD CONSTRAINT fk_training_code_approval_record
        FOREIGN KEY (code_approval_record_id) REFERENCES code_approval_record(id),
    ADD CONSTRAINT ck_training_run_spec_sha256
        CHECK (run_spec_sha256 IS NULL OR run_spec_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_input_model_sha256
        CHECK (input_model_sha256 IS NULL OR input_model_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_input_dataset_sha256
        CHECK (input_dataset_sha256 IS NULL OR input_dataset_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_input_code_sha256
        CHECK (input_code_sha256 IS NULL OR input_code_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_runtime_image_digest
        CHECK (runtime_image_digest IS NULL OR runtime_image_digest ~ '^sha256:[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_run_spec_complete
        CHECK (
            run_spec_json IS NULL OR (
                training_plan_id IS NOT NULL AND training_plan_version IS NOT NULL
                AND resource_profile_id IS NOT NULL AND run_spec_sha256 IS NOT NULL
                AND input_model_sha256 IS NOT NULL AND input_dataset_sha256 IS NOT NULL
                AND input_code_sha256 IS NOT NULL AND code_approval_record_id IS NOT NULL
                AND runtime_image IS NOT NULL
            )
        );

CREATE OR REPLACE FUNCTION fn_training_run_spec_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.run_spec_json IS NOT NULL AND (
        NEW.training_plan_id IS DISTINCT FROM OLD.training_plan_id
        OR NEW.training_plan_version IS DISTINCT FROM OLD.training_plan_version
        OR NEW.resource_profile_id IS DISTINCT FROM OLD.resource_profile_id
        OR NEW.run_spec_json IS DISTINCT FROM OLD.run_spec_json
        OR NEW.run_spec_sha256 IS DISTINCT FROM OLD.run_spec_sha256
        OR NEW.input_model_sha256 IS DISTINCT FROM OLD.input_model_sha256
        OR NEW.input_dataset_sha256 IS DISTINCT FROM OLD.input_dataset_sha256
        OR NEW.input_code_sha256 IS DISTINCT FROM OLD.input_code_sha256
        OR NEW.code_approval_record_id IS DISTINCT FROM OLD.code_approval_record_id
        OR NEW.runtime_image IS DISTINCT FROM OLD.runtime_image
        OR NEW.runtime_image_digest IS DISTINCT FROM OLD.runtime_image_digest
    ) THEN
        RAISE EXCEPTION 'training run spec is immutable after creation' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_training_run_spec_immutable
BEFORE UPDATE ON training_experiment_version
FOR EACH ROW EXECUTE FUNCTION fn_training_run_spec_reject_mutation();

CREATE INDEX IF NOT EXISTS idx_training_plan_version
    ON training_experiment_version(training_plan_id, training_plan_version);

CREATE INDEX IF NOT EXISTS idx_training_resource_profile
    ON training_experiment_version(resource_profile_id)
    WHERE resource_profile_id IS NOT NULL;
