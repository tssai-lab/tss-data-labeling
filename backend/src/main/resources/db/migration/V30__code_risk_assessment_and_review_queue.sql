ALTER TABLE code_validation_run
    ADD CONSTRAINT uk_code_validation_run_risk_evidence
        UNIQUE (id, version_id, artifact_sha256);

CREATE TABLE code_risk_assessment (
    id                      VARCHAR(64)  PRIMARY KEY,
    version_id              VARCHAR(64)  NOT NULL,
    validation_run_id       VARCHAR(64)  NOT NULL,
    artifact_sha256         VARCHAR(64)  NOT NULL,
    risk_policy_version     VARCHAR(128) NOT NULL,
    scanner_version         VARCHAR(128) NOT NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    risk_level              VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN',
    disposition             VARCHAR(32),
    finding_count           INTEGER      NOT NULL DEFAULT 0,
    error_code              VARCHAR(64),
    error_message           TEXT,
    requested_by_user_id    INTEGER,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_code_risk_assessment_evidence
        UNIQUE (id, version_id, artifact_sha256),
    CONSTRAINT fk_code_risk_assessment_version
        FOREIGN KEY (version_id) REFERENCES code_version (id),
    CONSTRAINT fk_code_risk_assessment_validation_evidence
        FOREIGN KEY (validation_run_id, version_id, artifact_sha256)
            REFERENCES code_validation_run (id, version_id, artifact_sha256),
    CONSTRAINT ck_code_risk_assessment_artifact_sha256
        CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_code_risk_assessment_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'ERROR', 'CANCELED')),
    CONSTRAINT ck_code_risk_assessment_risk_level
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    CONSTRAINT ck_code_risk_assessment_disposition
        CHECK (
            disposition IS NULL
            OR disposition IN ('AUTO_APPROVE', 'MANUAL_REVIEW', 'BLOCK')
        ),
    CONSTRAINT ck_code_risk_assessment_finding_count
        CHECK (finding_count >= 0),
    CONSTRAINT ck_code_risk_assessment_lifecycle
        CHECK (
            status NOT IN ('QUEUED', 'RUNNING', 'COMPLETED', 'ERROR', 'CANCELED')
            OR (
                (
                    status IN ('QUEUED', 'RUNNING')
                    AND completed_at IS NULL
                )
                OR
                (
                    status IN ('COMPLETED', 'ERROR', 'CANCELED')
                    AND completed_at IS NOT NULL
                )
            )
        ),
    CONSTRAINT ck_code_risk_assessment_completed_disposition
        CHECK (status <> 'COMPLETED' OR disposition IS NOT NULL)
);

CREATE TABLE code_risk_finding (
    id                      VARCHAR(64)   PRIMARY KEY,
    risk_assessment_id      VARCHAR(64)   NOT NULL,
    rule_id                 VARCHAR(128)  NOT NULL,
    severity                VARCHAR(32)   NOT NULL,
    category                VARCHAR(64)   NOT NULL,
    file_path               VARCHAR(1024),
    line_start              INTEGER,
    line_end                INTEGER,
    description             TEXT         NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_code_risk_finding_assessment
        FOREIGN KEY (risk_assessment_id) REFERENCES code_risk_assessment (id),
    CONSTRAINT ck_code_risk_finding_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_code_risk_finding_line_start
        CHECK (line_start IS NULL OR line_start >= 1),
    CONSTRAINT ck_code_risk_finding_line_end
        CHECK (
            line_end IS NULL
            OR (line_start IS NOT NULL AND line_end >= line_start)
        )
);

ALTER TABLE code_approval_record
    ADD COLUMN decision_source VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN risk_assessment_id VARCHAR(64),
    ADD COLUMN approval_policy_version VARCHAR(128);

UPDATE code_approval_record
SET decision_source = 'LEGACY'
WHERE decision = 'LEGACY_APPROVAL_IMPORTED';

ALTER TABLE code_approval_record
    DROP CONSTRAINT ck_code_approval_record_reviewer,
    ADD CONSTRAINT fk_code_approval_record_risk_assessment
        FOREIGN KEY (risk_assessment_id) REFERENCES code_risk_assessment (id),
    ADD CONSTRAINT ck_code_approval_record_decision_source
        CHECK (decision_source IN ('AUTO_POLICY', 'ADMIN', 'LEGACY')),
    ADD CONSTRAINT ck_code_approval_record_reviewer
        CHECK (
            (
                decision_source = 'AUTO_POLICY'
                AND decision IN ('APPROVED', 'REJECTED')
                AND reviewer_user_id IS NULL
                AND risk_assessment_id IS NOT NULL
                AND artifact_sha256 IS NOT NULL
                AND validation_run_id IS NOT NULL
                AND policy_version IS NOT NULL
                AND approval_policy_version IS NOT NULL
            )
            OR
            (
                decision_source = 'ADMIN'
                AND reviewer_user_id IS NOT NULL
            )
            OR
            (
                decision_source = 'LEGACY'
                AND decision = 'LEGACY_APPROVAL_IMPORTED'
                AND reviewer_user_id IS NULL
                AND risk_assessment_id IS NULL
            )
        );

ALTER TABLE code_version
    ADD COLUMN latest_risk_assessment_id VARCHAR(64),
    ADD COLUMN risk_status VARCHAR(32),
    ADD COLUMN risk_level VARCHAR(32),
    ADD COLUMN review_disposition VARCHAR(32),
    ADD COLUMN risk_policy_version VARCHAR(128),
    ADD CONSTRAINT fk_code_version_latest_risk_evidence
        FOREIGN KEY (latest_risk_assessment_id, id, artifact_sha256)
            REFERENCES code_risk_assessment (id, version_id, artifact_sha256),
    ADD CONSTRAINT ck_code_version_risk_status
        CHECK (
            risk_status IS NULL
            OR risk_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'ERROR', 'CANCELED')
        ),
    ADD CONSTRAINT ck_code_version_risk_level
        CHECK (
            risk_level IS NULL
            OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')
        ),
    ADD CONSTRAINT ck_code_version_review_disposition
        CHECK (
            review_disposition IS NULL
            OR review_disposition IN ('AUTO_APPROVE', 'MANUAL_REVIEW', 'BLOCK')
        ),
    ADD CONSTRAINT ck_code_version_risk_summary
        CHECK (
            (
                latest_risk_assessment_id IS NULL
                AND risk_status IS NULL
                AND risk_level IS NULL
                AND review_disposition IS NULL
                AND risk_policy_version IS NULL
            )
            OR
            (
                latest_risk_assessment_id IS NOT NULL
                AND artifact_sha256 IS NOT NULL
                AND risk_status IS NOT NULL
                AND risk_level IS NOT NULL
                AND risk_policy_version IS NOT NULL
            )
        );

ALTER TABLE code_asset_audit_log
    ADD COLUMN actor_type VARCHAR(32) NOT NULL DEFAULT 'USER',
    ALTER COLUMN actor_user_id DROP NOT NULL,
    ADD CONSTRAINT ck_code_asset_audit_log_actor
        CHECK (
            (actor_type = 'USER' AND actor_user_id IS NOT NULL)
            OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
        );

CREATE INDEX idx_code_risk_assessment_version_created
    ON code_risk_assessment (version_id, created_at DESC, id DESC);

CREATE INDEX idx_code_risk_assessment_status_created
    ON code_risk_assessment (status, created_at, id);

CREATE INDEX idx_code_risk_finding_assessment_location
    ON code_risk_finding (
        risk_assessment_id,
        file_path,
        line_start,
        id
    );

CREATE INDEX idx_code_approval_record_risk_assessment
    ON code_approval_record (risk_assessment_id)
    WHERE risk_assessment_id IS NOT NULL;

CREATE INDEX idx_code_version_pending_review_queue
    ON code_version (
        review_disposition,
        risk_level,
        created_at DESC,
        id DESC
    )
    WHERE deleted = FALSE AND approval_status = 'PENDING';
