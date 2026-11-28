CREATE TABLE online_model_connection (
    id UUID PRIMARY KEY,
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ai_execution ADD COLUMN online_model JSONB
    CHECK (online_model IS NULL OR jsonb_typeof(online_model) = 'object');

GRANT SELECT, INSERT, UPDATE ON online_model_connection TO tss_labeling_app;
