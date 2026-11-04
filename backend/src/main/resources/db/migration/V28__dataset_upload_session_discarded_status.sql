ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_status;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_status
        CHECK (status IN ('UPLOADING', 'COMPLETING', 'COMPLETED', 'DISCARDED'));
