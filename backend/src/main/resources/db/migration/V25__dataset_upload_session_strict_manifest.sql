ALTER TABLE dataset_upload_session
    ADD COLUMN strict_manifest BOOLEAN NOT NULL DEFAULT FALSE;
