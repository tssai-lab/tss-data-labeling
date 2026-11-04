-- V53 follows the code-asset name and abandoned-workspace migration.
ALTER TABLE dataset_upload_session
    ADD COLUMN completion_error_code VARCHAR(64),
    ADD COLUMN completion_error_message VARCHAR(512),
    ADD COLUMN completion_error_details JSONB,
    ADD COLUMN completion_failed_at TIMESTAMPTZ;

ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_status;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_status
        CHECK (status IN (
            'UPLOADING',
            'COMPLETING',
            'COMPLETED',
            'DISCARDED',
            'FAILED'
        ));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_completion_failure
        CHECK (
            (
                status = 'FAILED'
                AND completion_error_code IS NOT NULL
                AND completion_error_message IS NOT NULL
                AND completion_error_details IS NOT NULL
                AND completion_failed_at IS NOT NULL
            )
            OR
            (
                status <> 'FAILED'
                AND completion_error_code IS NULL
                AND completion_error_message IS NULL
                AND completion_error_details IS NULL
                AND completion_failed_at IS NULL
            )
        );
