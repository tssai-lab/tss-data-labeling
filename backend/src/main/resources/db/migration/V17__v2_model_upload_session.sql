ALTER TABLE model_upload_session
    ADD COLUMN target_asset_id VARCHAR(64);

ALTER TABLE model_upload_session
    ADD COLUMN model_name VARCHAR(255);

ALTER TABLE model_upload_session
    ADD COLUMN model_version VARCHAR(64);

ALTER TABLE model_upload_session
    ADD COLUMN task_type VARCHAR(64);

ALTER TABLE model_upload_session
    ADD COLUMN remark VARCHAR(1024);

ALTER TABLE model_upload_session
    ADD CONSTRAINT fk_model_upload_session_target_asset
        FOREIGN KEY (target_asset_id) REFERENCES model_asset (id)
        ON DELETE SET NULL;
