-- Native LS tasks are samples; CSV/images referenced by them are resources.
CREATE TABLE input_resource (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    batch_id UUID NOT NULL,
    source_path TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    media_type TEXT NOT NULL,
    metadata JSONB NOT NULL CHECK(jsonb_typeof(metadata)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(batch_id,source_path),
    FOREIGN KEY(batch_id,project_id) REFERENCES import_batch(id,project_id)
);
CREATE INDEX ix_input_resource_project ON input_resource(project_id,id);
ALTER TABLE import_batch ADD COLUMN resource_count INTEGER NOT NULL DEFAULT 0 CHECK(resource_count>=0);
GRANT SELECT,INSERT ON input_resource TO tss_labeling_app;
