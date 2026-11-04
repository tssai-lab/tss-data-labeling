CREATE TABLE actor_role_grant (
    actor_id UUID NOT NULL REFERENCES actor(id),
    role_code TEXT NOT NULL,
    granted_by UUID REFERENCES actor(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, role_code),
    CONSTRAINT ck_actor_role_code CHECK (role_code IN ('LABELING_ADMIN'))
);

CREATE TABLE idempotency_record (
    actor_id UUID NOT NULL REFERENCES actor(id),
    operation TEXT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_operation CHECK (length(btrim(operation)) > 0)
);

CREATE TABLE project (
    id UUID PRIMARY KEY,
    owner_actor_id UUID NOT NULL REFERENCES actor(id),
    name VARCHAR(120) NOT NULL,
    description TEXT,
    task_type VARCHAR(80) NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    visibility TEXT NOT NULL DEFAULT 'PLATFORM',
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_project_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_project_task_type CHECK (length(btrim(task_type)) > 0),
    CONSTRAINT ck_project_status CHECK (status IN ('DRAFT', 'OPEN', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT ck_project_visibility CHECK (visibility = 'PLATFORM'),
    CONSTRAINT ck_project_revision CHECK (revision > 0),
    CONSTRAINT uq_project_id_owner UNIQUE (id, owner_actor_id)
);

CREATE INDEX ix_project_owner_created
    ON project (owner_actor_id, created_at DESC, id DESC);

CREATE TABLE spec_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    version_no INTEGER NOT NULL,
    task_type VARCHAR(80) NOT NULL,
    labels JSONB NOT NULL,
    instructions TEXT NOT NULL DEFAULT '',
    editor_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    schema_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_spec_version_number UNIQUE (project_id, version_no),
    CONSTRAINT uq_spec_version_project UNIQUE (id, project_id),
    CONSTRAINT ck_spec_version_number CHECK (version_no > 0),
    CONSTRAINT ck_spec_labels_array CHECK (jsonb_typeof(labels) = 'array'),
    CONSTRAINT ck_spec_editor_config_object CHECK (jsonb_typeof(editor_config) = 'object'),
    CONSTRAINT ck_spec_task_type CHECK (length(btrim(task_type)) > 0),
    CONSTRAINT ck_spec_schema_version CHECK (length(btrim(schema_version)) > 0)
);

CREATE INDEX ix_spec_version_project
    ON spec_version (project_id, version_no DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON actor_role_grant TO tss_labeling_app;
GRANT SELECT, INSERT ON idempotency_record TO tss_labeling_app;
GRANT SELECT, INSERT, UPDATE ON project, spec_version TO tss_labeling_app;
