ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS file_count BIGINT;

CREATE INDEX IF NOT EXISTS idx_dataset_asset_catalog_owner_type_time
    ON dataset_asset (owner_user_id, deleted, type, updated_at DESC, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_dataset_asset_catalog_admin_type_time
    ON dataset_asset (deleted, type, updated_at DESC, created_at DESC, id);
