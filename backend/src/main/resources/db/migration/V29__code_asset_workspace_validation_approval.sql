ALTER TABLE code_asset
    ADD COLUMN purpose VARCHAR(1024),
    ADD COLUMN runtime VARCHAR(128),
    ADD COLUMN entry_script VARCHAR(1024),
    ADD COLUMN training_type VARCHAR(128),
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE code_version
    ADD COLUMN artifact_sha256 VARCHAR(64),
    ADD COLUMN validation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN',
    ADD COLUMN validation_policy_version VARCHAR(128),
    ADD COLUMN purpose VARCHAR(1024),
    ADD COLUMN runtime VARCHAR(128),
    ADD COLUMN entry_script VARCHAR(1024),
    ADD COLUMN training_type VARCHAR(128),
    ADD COLUMN training_profile VARCHAR(128),
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deprecated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

UPDATE code_version version_row
SET purpose = asset_row.purpose,
    runtime = asset_row.runtime,
    entry_script = COALESCE(
        asset_row.entry_script,
        CASE asset_row.training_profile
            WHEN 'image_text_consistency_fusion_logreg'
                THEN 'scripts/training/train_fusion_baseline.py'
            ELSE NULL
        END
    ),
    training_type = asset_row.training_type,
    training_profile = asset_row.training_profile
FROM code_asset asset_row
WHERE asset_row.id = version_row.asset_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM code_version version_row
        LEFT JOIN code_asset asset_row ON asset_row.id = version_row.asset_id
        WHERE asset_row.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V29 cannot add fk_code_version_asset: orphan code_version.asset_id values exist';
    END IF;
END
$$;

ALTER TABLE code_version
    ADD CONSTRAINT fk_code_version_asset
        FOREIGN KEY (asset_id) REFERENCES code_asset (id),
    ADD CONSTRAINT ck_code_version_status
        CHECK (status IN ('READY', 'DEPRECATED', 'ARCHIVED')),
    ADD CONSTRAINT ck_code_version_validation_status
        CHECK (validation_status IN ('NOT_RUN', 'PASSED', 'FAILED')),
    ADD CONSTRAINT ck_code_version_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'));

CREATE TABLE code_workspace (
    id                  VARCHAR(64)  PRIMARY KEY,
    asset_id            VARCHAR(64)  NOT NULL,
    base_version_id     VARCHAR(64),
    closed_version_id   VARCHAR(64),
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    revision            BIGINT       NOT NULL DEFAULT 0,
    owner_user_id       INTEGER      NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at           TIMESTAMP WITH TIME ZONE,
    deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_code_workspace_status
        CHECK (status IN ('OPEN', 'PUBLISHED', 'ABANDONED')),
    CONSTRAINT ck_code_workspace_revision
        CHECK (revision >= 0),
    CONSTRAINT fk_code_workspace_asset
        FOREIGN KEY (asset_id) REFERENCES code_asset (id),
    CONSTRAINT fk_code_workspace_base_version
        FOREIGN KEY (base_version_id) REFERENCES code_version (id),
    CONSTRAINT fk_code_workspace_closed_version
        FOREIGN KEY (closed_version_id) REFERENCES code_version (id)
);

CREATE TABLE code_workspace_file_delta (
    id                  VARCHAR(64)   PRIMARY KEY,
    workspace_id        VARCHAR(64)   NOT NULL,
    path                VARCHAR(1024) NOT NULL,
    operation           VARCHAR(32)   NOT NULL,
    content_bytes       BYTEA,
    content_hash        VARCHAR(64),
    size_bytes          BIGINT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_code_workspace_file_delta_workspace
        FOREIGN KEY (workspace_id) REFERENCES code_workspace (id),
    CONSTRAINT ck_code_workspace_file_delta_operation
        CHECK (operation IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_code_workspace_file_delta_payload
        CHECK (
            (
                operation = 'UPSERT'
                AND content_bytes IS NOT NULL
                AND content_hash IS NOT NULL
                AND size_bytes IS NOT NULL
                AND size_bytes >= 0
                AND size_bytes = octet_length(content_bytes)
                AND content_hash ~ '^[0-9a-f]{64}$'
            )
            OR
            (
                operation = 'DELETE'
                AND content_bytes IS NULL
                AND content_hash IS NULL
                AND size_bytes IS NULL
            )
        ),
    CONSTRAINT uk_code_workspace_file_delta_path
        UNIQUE (workspace_id, path)
);

CREATE TABLE code_validation_run (
    id                      VARCHAR(64)  PRIMARY KEY,
    version_id              VARCHAR(64)  NOT NULL,
    artifact_sha256         VARCHAR(64)  NOT NULL,
    policy_version          VARCHAR(128) NOT NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'NOT_RUN',
    failure_code            VARCHAR(64),
    failure_message         TEXT,
    requested_by_user_id    INTEGER      NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_code_validation_run_version
        FOREIGN KEY (version_id) REFERENCES code_version (id),
    CONSTRAINT ck_code_validation_run_status
        CHECK (status IN ('NOT_RUN', 'PASSED', 'FAILED'))
);

CREATE TABLE code_approval_record (
    id                  VARCHAR(64)  PRIMARY KEY,
    version_id          VARCHAR(64)  NOT NULL,
    artifact_sha256     VARCHAR(64),
    validation_run_id   VARCHAR(64),
    policy_version      VARCHAR(128),
    decision            VARCHAR(32)  NOT NULL,
    reason              TEXT,
    reviewer_user_id    INTEGER,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_code_approval_record_version
        FOREIGN KEY (version_id) REFERENCES code_version (id),
    CONSTRAINT fk_code_approval_record_validation_run
        FOREIGN KEY (validation_run_id) REFERENCES code_validation_run (id),
    CONSTRAINT ck_code_approval_record_decision
        CHECK (decision IN (
            'PENDING',
            'APPROVED',
            'REJECTED',
            'REVOKED',
            'LEGACY_APPROVAL_IMPORTED'
        )),
    CONSTRAINT ck_code_approval_record_reviewer
        CHECK (
            (decision = 'LEGACY_APPROVAL_IMPORTED' AND reviewer_user_id IS NULL)
            OR (decision <> 'LEGACY_APPROVAL_IMPORTED' AND reviewer_user_id IS NOT NULL)
        )
);

CREATE TABLE code_asset_audit_log (
    id                  VARCHAR(64)  PRIMARY KEY,
    asset_id            VARCHAR(64)  NOT NULL,
    version_id          VARCHAR(64),
    workspace_id        VARCHAR(64),
    action              VARCHAR(64)  NOT NULL,
    actor_user_id       INTEGER      NOT NULL,
    metadata_json       TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_code_asset_audit_log_asset
        FOREIGN KEY (asset_id) REFERENCES code_asset (id),
    CONSTRAINT fk_code_asset_audit_log_version
        FOREIGN KEY (version_id) REFERENCES code_version (id),
    CONSTRAINT fk_code_asset_audit_log_workspace
        FOREIGN KEY (workspace_id) REFERENCES code_workspace (id)
);

CREATE FUNCTION fn_code_asset_audit_log_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'code_asset_audit_log is append-only; % is not allowed', TG_OP
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_code_asset_audit_log_append_only
BEFORE UPDATE OR DELETE ON code_asset_audit_log
FOR EACH ROW
EXECUTE FUNCTION fn_code_asset_audit_log_reject_mutation();

CREATE INDEX idx_code_asset_owner_deleted_created
    ON code_asset (owner_user_id, deleted, created_at DESC, id);

CREATE INDEX idx_code_version_asset_created
    ON code_version (asset_id, deleted, created_at DESC, id);

CREATE INDEX idx_code_workspace_owner_deleted_updated
    ON code_workspace (owner_user_id, deleted, updated_at DESC, id);

CREATE INDEX idx_code_workspace_asset_status_deleted
    ON code_workspace (asset_id, status, deleted);

CREATE UNIQUE INDEX uk_code_workspace_open_asset
    ON code_workspace (asset_id)
    WHERE status = 'OPEN' AND deleted = FALSE;

CREATE INDEX idx_code_workspace_file_delta_workspace_updated
    ON code_workspace_file_delta (workspace_id, updated_at DESC, id);

CREATE INDEX idx_code_validation_run_version_created
    ON code_validation_run (version_id, created_at DESC, id DESC);

CREATE INDEX idx_code_approval_record_version_created
    ON code_approval_record (version_id, created_at DESC, id DESC);

CREATE INDEX idx_code_approval_record_validation_run
    ON code_approval_record (validation_run_id);

CREATE INDEX idx_code_asset_audit_log_asset_created
    ON code_asset_audit_log (asset_id, created_at DESC, id DESC);

CREATE INDEX idx_code_asset_audit_log_version_created
    ON code_asset_audit_log (version_id, created_at DESC, id DESC);

CREATE INDEX idx_code_asset_audit_log_workspace_created
    ON code_asset_audit_log (workspace_id, created_at DESC, id DESC);

INSERT INTO code_approval_record (
    id,
    version_id,
    artifact_sha256,
    validation_run_id,
    policy_version,
    decision,
    reason,
    reviewer_user_id,
    created_at
)
SELECT
    'legacy-' || md5(version_row.id),
    version_row.id,
    version_row.artifact_sha256,
    NULL,
    NULL,
    'LEGACY_APPROVAL_IMPORTED',
    'Imported legacy approval; validation and approval must be performed again',
    NULL,
    COALESCE(version_row.updated_at, version_row.created_at, CURRENT_TIMESTAMP)
FROM code_version version_row
WHERE version_row.approval_status = 'APPROVED';

UPDATE code_version
SET approval_status = 'PENDING'
WHERE approval_status = 'APPROVED';
