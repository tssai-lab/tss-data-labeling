CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_version_one_active_draft
    ON dataset_version (asset_id)
    WHERE status = 'DRAFT' AND deleted = false;
