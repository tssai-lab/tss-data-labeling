ALTER TABLE model_version
    ADD COLUMN artifact_spec_id VARCHAR(128);

ALTER TABLE dataset_version
    ADD COLUMN artifact_spec_id VARCHAR(128);

ALTER TABLE model_upload_session
    ADD COLUMN artifact_spec_id VARCHAR(128);

ALTER TABLE dataset_upload_session
    ADD COLUMN artifact_spec_id VARCHAR(128);

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_artifact_spec_id
        CHECK (
            artifact_spec_id IS NULL
            OR artifact_spec_id ~ '^model\.[a-z0-9][a-z0-9.-]{1,95}/v[1-9][0-9]*$'
        );

ALTER TABLE model_upload_session
    ADD CONSTRAINT ck_model_upload_session_artifact_spec_id
        CHECK (
            artifact_spec_id IS NULL
            OR artifact_spec_id ~ '^model\.[a-z0-9][a-z0-9.-]{1,95}/v[1-9][0-9]*$'
        );

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_artifact_spec_id
        CHECK (
            artifact_spec_id IS NULL
            OR artifact_spec_id ~ '^dataset\.[a-z0-9][a-z0-9.-]{1,95}/v[1-9][0-9]*$'
        );

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_artifact_spec_id
        CHECK (
            artifact_spec_id IS NULL
            OR artifact_spec_id ~ '^dataset\.[a-z0-9][a-z0-9.-]{1,95}/v[1-9][0-9]*$'
        );

CREATE INDEX idx_model_version_ready_artifact_spec
    ON model_version (owner_user_id, artifact_spec_id, created_at DESC, id DESC)
    WHERE status = 'READY'
      AND deleted = FALSE
      AND artifact_spec_id IS NOT NULL;

CREATE INDEX idx_dataset_version_ready_artifact_spec
    ON dataset_version (owner_user_id, artifact_spec_id, created_at DESC, id DESC)
    WHERE status = 'READY'
      AND deleted = FALSE
      AND artifact_spec_id IS NOT NULL;

COMMENT ON COLUMN model_version.artifact_spec_id
    IS 'Server-verified model artifact contract; NULL means unverified or no registered match';
COMMENT ON COLUMN dataset_version.artifact_spec_id
    IS 'Server-verified dataset artifact contract; NULL means unverified or no registered match';
COMMENT ON COLUMN model_upload_session.artifact_spec_id
    IS 'Frozen server recognition result for an upload completion; never trusted from the client';
COMMENT ON COLUMN dataset_upload_session.artifact_spec_id
    IS 'Frozen server recognition result for an upload completion; never trusted from the client';
