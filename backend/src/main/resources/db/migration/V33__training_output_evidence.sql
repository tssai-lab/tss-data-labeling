ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS training_output_json TEXT,
    ADD COLUMN IF NOT EXISTS training_output_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS training_output_object_name VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS training_output_size_bytes BIGINT;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_output_sha256
        CHECK (training_output_sha256 IS NULL OR training_output_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_training_output_size
        CHECK (training_output_size_bytes IS NULL OR training_output_size_bytes BETWEEN 1 AND 1048576),
    ADD CONSTRAINT ck_training_output_complete
        CHECK (
            (training_output_json IS NULL AND training_output_sha256 IS NULL
             AND training_output_object_name IS NULL AND training_output_size_bytes IS NULL)
            OR
            (training_output_json IS NOT NULL AND training_output_sha256 IS NOT NULL
             AND training_output_object_name IS NOT NULL AND training_output_size_bytes IS NOT NULL)
        );

CREATE OR REPLACE FUNCTION fn_training_output_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.training_output_json IS NOT NULL AND (
        NEW.training_output_json IS DISTINCT FROM OLD.training_output_json
        OR NEW.training_output_sha256 IS DISTINCT FROM OLD.training_output_sha256
        OR NEW.training_output_object_name IS DISTINCT FROM OLD.training_output_object_name
        OR NEW.training_output_size_bytes IS DISTINCT FROM OLD.training_output_size_bytes
    ) THEN
        RAISE EXCEPTION 'training output evidence is immutable after acceptance' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_training_output_immutable
BEFORE UPDATE ON training_experiment_version
FOR EACH ROW EXECUTE FUNCTION fn_training_output_reject_mutation();

CREATE UNIQUE INDEX IF NOT EXISTS uk_training_output_object_name
    ON training_experiment_version(training_output_object_name)
    WHERE training_output_object_name IS NOT NULL;
