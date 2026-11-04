-- V52 follows the upstream V50/V51 migrations.
ALTER TABLE code_asset
    ADD COLUMN normalized_name TEXT;

ALTER TABLE code_asset
    ADD CONSTRAINT ck_code_asset_name_not_blank
        CHECK (name IS NOT NULL AND normalize_asset_name(name) <> '') NOT VALID;

-- Preserve historical duplicate rows while letting one deterministic representative
-- claim each owner-scoped name. Additional duplicates remain unclaimed until renamed.
WITH ranked_names AS (
    SELECT
        id,
        normalize_asset_name(name) AS normalized_name,
        row_number() OVER (
            PARTITION BY owner_user_id, normalize_asset_name(name)
            ORDER BY id
        ) AS position
    FROM code_asset
    WHERE deleted = FALSE
      AND name IS NOT NULL
      AND normalize_asset_name(name) <> ''
)
UPDATE code_asset asset
SET normalized_name = ranked_names.normalized_name
FROM ranked_names
WHERE asset.id = ranked_names.id
  AND ranked_names.position = 1;

CREATE UNIQUE INDEX uk_code_asset_owner_normalized_name
    ON code_asset (
        (coalesce(owner_user_id::text, '<legacy-null-owner>')),
        normalized_name
    )
    WHERE deleted = FALSE AND normalized_name IS NOT NULL;

CREATE TRIGGER trg_code_asset_normalized_name
BEFORE INSERT OR UPDATE ON code_asset
FOR EACH ROW
EXECUTE FUNCTION maintain_asset_normalized_name();

-- ABANDONED workspaces remain as audit tombstones, but no longer reserve a
-- user-visible version label or internal sequence number.
UPDATE dataset_version
SET deleted = TRUE,
    deleted_at = COALESCE(deleted_at, updated_at, created_at, CURRENT_TIMESTAMP),
    active_draft_asset_id = NULL
WHERE status = 'ABANDONED'
  AND deleted = FALSE;

DROP INDEX uk_dataset_version_asset_version;

CREATE UNIQUE INDEX uk_dataset_version_asset_version
    ON dataset_version (asset_id, version)
    WHERE NOT (deleted = TRUE AND status = 'ABANDONED');

DROP INDEX uk_dataset_version_asset_version_no;

CREATE UNIQUE INDEX uk_dataset_version_asset_version_no
    ON dataset_version (asset_id, version_no)
    WHERE NOT (deleted = TRUE AND status = 'ABANDONED');
