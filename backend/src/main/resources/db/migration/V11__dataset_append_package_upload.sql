ALTER TABLE dataset_upload_session
    ADD COLUMN upload_purpose VARCHAR(32) NOT NULL DEFAULT 'INITIAL_DATASET';

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_purpose
        CHECK (upload_purpose IN ('INITIAL_DATASET', 'APPEND_PACKAGE'));

CREATE INDEX idx_dataset_upload_purpose
    ON dataset_upload_session (upload_purpose);
