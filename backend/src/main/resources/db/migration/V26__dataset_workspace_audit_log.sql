CREATE TABLE dataset_workspace_audit_log (
    id                  VARCHAR(64)  PRIMARY KEY,
    dataset_asset_id    VARCHAR(64)  NOT NULL REFERENCES dataset_asset(id),
    dataset_version_id  VARCHAR(64)  NOT NULL REFERENCES dataset_version(id),
    parent_version_id   VARCHAR(64),
    operation           VARCHAR(64)  NOT NULL,
    actor_type          VARCHAR(32)  NOT NULL,
    actor_user_id       INTEGER,
    owner_user_id       INTEGER,
    target_type         VARCHAR(64),
    target_id           VARCHAR(128),
    import_job_id       VARCHAR(64),
    package_id          VARCHAR(64),
    sample_id           VARCHAR(64),
    details             JSONB,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_workspace_audit_version_created
    ON dataset_workspace_audit_log (dataset_version_id, created_at DESC, id DESC);

CREATE INDEX idx_workspace_audit_asset_created
    ON dataset_workspace_audit_log (dataset_asset_id, created_at DESC, id DESC);

CREATE INDEX idx_workspace_audit_import_job
    ON dataset_workspace_audit_log (import_job_id);

CREATE INDEX idx_workspace_audit_operation
    ON dataset_workspace_audit_log (operation);
