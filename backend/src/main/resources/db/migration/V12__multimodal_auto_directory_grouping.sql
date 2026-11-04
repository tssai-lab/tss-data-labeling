ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_sample_grouping;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_sample_grouping
        CHECK (
            sample_grouping IS NULL
            OR sample_grouping IN ('MANIFEST', 'AUTO_DIRECTORY')
        );
