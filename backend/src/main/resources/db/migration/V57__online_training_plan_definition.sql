CREATE TABLE training_plan_definition (
    id BIGSERIAL PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    plan_version VARCHAR(16) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    yaml_content TEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    imported_by_user_id INTEGER NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    published_by_user_id INTEGER NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    disabled_by_user_id INTEGER,
    disabled_at TIMESTAMPTZ,
    CONSTRAINT uq_training_plan_definition_version UNIQUE (plan_id, plan_version),
    CONSTRAINT ck_training_plan_definition_plan_id
        CHECK (plan_id ~ '^[a-z][a-z0-9_]{2,63}$'),
    CONSTRAINT ck_training_plan_definition_version
        CHECK (plan_version ~ '^v[1-9][0-9]*$'),
    CONSTRAINT ck_training_plan_definition_sha256
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_training_plan_definition_schema
        CHECK (schema_version = 'tss.training.plan/v2'),
    CONSTRAINT ck_training_plan_definition_yaml_size
        CHECK (OCTET_LENGTH(yaml_content) BETWEEN 1 AND 262144),
    CONSTRAINT ck_training_plan_definition_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_training_plan_definition_disabled_evidence
        CHECK (
            (status = 'ACTIVE' AND disabled_by_user_id IS NULL AND disabled_at IS NULL)
            OR
            (status = 'DISABLED' AND disabled_by_user_id IS NOT NULL AND disabled_at IS NOT NULL)
        ),
    CONSTRAINT ck_training_plan_definition_time_order
        CHECK (imported_at <= published_at AND (disabled_at IS NULL OR disabled_at >= published_at))
);

CREATE UNIQUE INDEX uq_training_plan_definition_active
    ON training_plan_definition (plan_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_training_plan_definition_history
    ON training_plan_definition (plan_id, published_at DESC, id DESC);

COMMENT ON TABLE training_plan_definition
    IS 'Immutable online training-plan YAML versions; lifecycle changes only update status and actor evidence';
COMMENT ON COLUMN training_plan_definition.yaml_content
    IS 'Original validated UTF-8 YAML bytes decoded without normalization';
COMMENT ON COLUMN training_plan_definition.content_sha256
    IS 'SHA-256 of the exact uploaded YAML bytes used for preview and publish compare-and-set';
