ALTER TABLE model_asset
    DROP CONSTRAINT IF EXISTS ck_model_asset_type;

ALTER TABLE model_asset
    ADD CONSTRAINT ck_model_asset_type
        CHECK (type IS NULL OR type IN ('CV', 'NLP', 'POINT_CLOUD', 'ROBOT'));

ALTER TABLE dataset_asset
    DROP CONSTRAINT IF EXISTS ck_dataset_asset_type;

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_type
        CHECK (type IS NULL OR type IN ('CV', 'NLP', 'POINT_CLOUD', 'ROBOT'));

ALTER TABLE dataset_upload_session
    DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_task_type;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_task_type
        CHECK (task_type IN ('CV', 'NLP', 'POINT_CLOUD', 'ROBOT'));
