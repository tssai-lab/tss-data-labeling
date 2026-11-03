ALTER TABLE dataset_version
    ADD COLUMN IF NOT EXISTS active_draft_asset_id VARCHAR(64);

UPDATE dataset_version
SET active_draft_asset_id =
        CASE
            WHEN status = 'DRAFT' AND deleted = false THEN asset_id
            ELSE NULL
        END;

ALTER TABLE dataset_version
    DROP CONSTRAINT IF EXISTS ck_dataset_version_active_draft_asset_id;

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_active_draft_asset_id
        CHECK (
            (
                status = 'DRAFT'
                AND deleted = false
                AND active_draft_asset_id IS NOT NULL
                AND active_draft_asset_id = asset_id
            )
            OR (
                (status <> 'DRAFT' OR deleted = true)
                AND active_draft_asset_id IS NULL
            )
        );

DROP INDEX IF EXISTS uk_dataset_version_one_active_draft;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_version_one_active_draft
    ON dataset_version (active_draft_asset_id);
