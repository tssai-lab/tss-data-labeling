ALTER TABLE dataset_version
    ADD COLUMN workspace_head_version_id VARCHAR(64);

UPDATE dataset_version workspace
SET workspace_head_version_id = asset.current_version_id
FROM dataset_asset asset
WHERE workspace.asset_id = asset.id
  AND workspace.status = 'DRAFT'
  AND workspace.deleted = FALSE
  AND workspace.workspace_head_version_id IS NULL;

ALTER TABLE dataset_version
    ADD CONSTRAINT fk_dataset_version_workspace_head
        FOREIGN KEY (workspace_head_version_id) REFERENCES dataset_version (id);

COMMENT ON COLUMN dataset_version.workspace_head_version_id IS
    'Asset current READY version captured when a dataset workspace is created';
