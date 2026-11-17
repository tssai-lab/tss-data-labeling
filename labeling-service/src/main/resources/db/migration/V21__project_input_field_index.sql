-- Input contents stay in versioned files; index only native top-level field names.
CREATE TABLE sample_input_index (
    sample_id UUID PRIMARY KEY REFERENCES sample(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES project(id),
    fields JSONB NOT NULL CHECK(jsonb_typeof(fields)='array'),
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_sample_input_index_project ON sample_input_index(project_id,sample_id);
GRANT SELECT,INSERT ON sample_input_index TO tss_labeling_app;

CREATE FUNCTION index_native_sample_fields() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF jsonb_typeof(NEW.metadata->'inputFields')='array' THEN
        INSERT INTO sample_input_index(sample_id,project_id,fields)
        VALUES(NEW.id,NEW.project_id,NEW.metadata->'inputFields') ON CONFLICT DO NOTHING;
    ELSIF NEW.media_type LIKE 'image/%' THEN
        INSERT INTO sample_input_index(sample_id,project_id,fields)
        VALUES(NEW.id,NEW.project_id,'["image"]'::jsonb) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_sample_input_index AFTER INSERT ON sample
FOR EACH ROW EXECUTE FUNCTION index_native_sample_fields();

INSERT INTO sample_input_index(sample_id,project_id,fields)
SELECT id,project_id,metadata->'inputFields' FROM sample
WHERE jsonb_typeof(metadata->'inputFields')='array' ON CONFLICT DO NOTHING;
INSERT INTO sample_input_index(sample_id,project_id,fields)
SELECT id,project_id,'["image"]'::jsonb FROM sample
WHERE media_type LIKE 'image/%' ON CONFLICT DO NOTHING;
