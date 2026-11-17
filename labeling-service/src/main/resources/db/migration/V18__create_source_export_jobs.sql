CREATE TABLE source_export_job (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES final_snapshot(id),
    owner_actor_id UUID NOT NULL REFERENCES actor(id),
    format VARCHAR(40) NOT NULL,
    status TEXT NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','READY','FAILED')),
    file_size BIGINT,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
CREATE INDEX ix_source_export_queue ON source_export_job(created_at,id) WHERE status='QUEUED';
GRANT SELECT,INSERT,UPDATE ON source_export_job TO tss_labeling_app;
