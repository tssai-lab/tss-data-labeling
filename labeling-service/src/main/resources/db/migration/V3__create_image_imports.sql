ALTER TABLE idempotency_record ADD COLUMN response_body JSONB;

CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    status TEXT NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    original_name TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    received_bytes BIGINT NOT NULL DEFAULT 0 CHECK (received_bytes >= 0 AND received_bytes <= size_bytes),
    chunk_size_bytes INTEGER NOT NULL CHECK (chunk_size_bytes > 0),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    original_complete BOOLEAN NOT NULL DEFAULT FALSE,
    valid_sample_count INTEGER NOT NULL DEFAULT 0 CHECK (valid_sample_count >= 0),
    error_count INTEGER NOT NULL DEFAULT 0 CHECK (error_count >= 0),
    errors JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(errors) = 'array'),
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, project_id)
);
CREATE INDEX ix_import_project ON import_batch(project_id, created_at, id);
CREATE INDEX ix_import_queue ON import_batch(created_at, id) WHERE status = 'PROCESSING';

CREATE TABLE import_chunk (
    batch_id UUID NOT NULL REFERENCES import_batch(id),
    byte_offset BIGINT NOT NULL CHECK (byte_offset >= 0),
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (batch_id, byte_offset)
);

CREATE TABLE sample (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    batch_id UUID NOT NULL,
    source_path TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    media_type TEXT NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id, source_path),
    UNIQUE (id, project_id),
    FOREIGN KEY (batch_id, project_id) REFERENCES import_batch(id, project_id)
);
CREATE INDEX ix_sample_batch_page ON sample(batch_id, id);

GRANT SELECT, INSERT, UPDATE ON import_batch TO tss_labeling_app;
GRANT SELECT, INSERT ON import_chunk, sample TO tss_labeling_app;
GRANT UPDATE (response_body) ON idempotency_record TO tss_labeling_app;
