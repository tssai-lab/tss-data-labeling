CREATE TABLE platform_system_config (
    id                          VARCHAR(32) PRIMARY KEY,
    training_code_review_mode   VARCHAR(32) NOT NULL DEFAULT 'STANDARD_REVIEW',
    updated_by_user_id          INTEGER,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_platform_system_config_singleton
        CHECK (id = 'GLOBAL'),
    CONSTRAINT ck_platform_system_config_training_code_review_mode
        CHECK (training_code_review_mode IN ('DIRECT_PASS', 'STANDARD_REVIEW')),
    CONSTRAINT ck_platform_system_config_updated_by
        CHECK (updated_by_user_id IS NULL OR updated_by_user_id > 0)
);

INSERT INTO platform_system_config (
    id,
    training_code_review_mode,
    updated_by_user_id,
    created_at,
    updated_at
)
VALUES (
    'GLOBAL',
    'STANDARD_REVIEW',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

ALTER TABLE code_risk_assessment
    DROP CONSTRAINT ck_code_risk_assessment_disposition,
    ADD CONSTRAINT ck_code_risk_assessment_disposition
        CHECK (
            disposition IS NULL
            OR disposition IN (
                'AUTO_APPROVE',
                'MANUAL_REVIEW',
                'BLOCK',
                'DIRECT_PASS'
            )
        );

ALTER TABLE code_version
    DROP CONSTRAINT ck_code_version_review_disposition,
    ADD CONSTRAINT ck_code_version_review_disposition
        CHECK (
            review_disposition IS NULL
            OR review_disposition IN (
                'AUTO_APPROVE',
                'MANUAL_REVIEW',
                'BLOCK',
                'DIRECT_PASS'
            )
        );

ALTER TABLE code_approval_record
    DROP CONSTRAINT ck_code_approval_record_decision_source,
    DROP CONSTRAINT ck_code_approval_record_reviewer,
    ADD CONSTRAINT ck_code_approval_record_decision_source
        CHECK (
            decision_source IN (
                'AUTO_POLICY',
                'ADMIN',
                'LEGACY',
                'SYSTEM_CONFIG'
            )
        ),
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
                decision_source = 'SYSTEM_CONFIG'
                AND decision = 'APPROVED'
                AND reviewer_user_id IS NULL
                AND risk_assessment_id IS NOT NULL
                AND artifact_sha256 IS NOT NULL
                AND validation_run_id IS NOT NULL
                AND policy_version IS NOT NULL
                AND approval_policy_version =
                    'training-code-direct-pass-approval-v1'
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
