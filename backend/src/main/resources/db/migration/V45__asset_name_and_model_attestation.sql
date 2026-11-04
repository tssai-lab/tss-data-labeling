ALTER TABLE model_asset
    ADD COLUMN normalized_name TEXT;

ALTER TABLE dataset_asset
    ADD COLUMN normalized_name TEXT;

CREATE OR REPLACE FUNCTION normalize_asset_name(value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
RETURNS NULL ON NULL INPUT
AS $$
    SELECT lower(regexp_replace(
            value,
            '^[[:space:]]+|[[:space:]]+$',
            '',
            'g'
    ));
$$;

ALTER TABLE model_asset
    ADD CONSTRAINT ck_model_asset_name_not_blank
        CHECK (name IS NOT NULL AND normalize_asset_name(name) <> '') NOT VALID;

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_name_not_blank
        CHECK (name IS NOT NULL AND normalize_asset_name(name) <> '') NOT VALID;

-- Preserve historical duplicate rows while letting one deterministic representative
-- claim each name. Additional duplicates remain unclaimed until they are renamed.
WITH ranked_names AS (
    SELECT
        id,
        normalize_asset_name(name) AS normalized_name,
        row_number() OVER (
            PARTITION BY owner_user_id, normalize_asset_name(name)
            ORDER BY id
        ) AS position
    FROM model_asset
    WHERE deleted = FALSE
      AND name IS NOT NULL
      AND normalize_asset_name(name) <> ''
)
UPDATE model_asset asset
SET normalized_name = ranked_names.normalized_name
FROM ranked_names
WHERE asset.id = ranked_names.id
  AND ranked_names.position = 1;

WITH ranked_names AS (
    SELECT
        id,
        normalize_asset_name(name) AS normalized_name,
        row_number() OVER (
            PARTITION BY owner_user_id, normalize_asset_name(name)
            ORDER BY id
        ) AS position
    FROM dataset_asset
    WHERE deleted = FALSE
      AND name IS NOT NULL
      AND normalize_asset_name(name) <> ''
)
UPDATE dataset_asset asset
SET normalized_name = ranked_names.normalized_name
FROM ranked_names
WHERE asset.id = ranked_names.id
  AND ranked_names.position = 1;

CREATE UNIQUE INDEX uk_model_asset_owner_normalized_name
    ON model_asset (
        (coalesce(owner_user_id::text, '<legacy-null-owner>')),
        normalized_name
    )
    WHERE deleted = FALSE AND normalized_name IS NOT NULL;

CREATE UNIQUE INDEX uk_dataset_asset_owner_normalized_name
    ON dataset_asset (
        (coalesce(owner_user_id::text, '<legacy-null-owner>')),
        normalized_name
    )
    WHERE deleted = FALSE AND normalized_name IS NOT NULL;

CREATE OR REPLACE FUNCTION maintain_asset_normalized_name()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.deleted = TRUE THEN
        NEW.normalized_name := NULL;
    ELSIF TG_OP = 'INSERT'
            OR NEW.name IS DISTINCT FROM OLD.name
            OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
            OR NEW.deleted IS DISTINCT FROM OLD.deleted
            OR OLD.normalized_name IS NOT NULL THEN
        NEW.normalized_name := normalize_asset_name(NEW.name);
    ELSE
        -- Leave unresolved historical duplicates unclaimed until they are renamed.
        NEW.normalized_name := OLD.normalized_name;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_model_asset_normalized_name
BEFORE INSERT OR UPDATE ON model_asset
FOR EACH ROW
EXECUTE FUNCTION maintain_asset_normalized_name();

CREATE TRIGGER trg_dataset_asset_normalized_name
BEFORE INSERT OR UPDATE ON dataset_asset
FOR EACH ROW
EXECUTE FUNCTION maintain_asset_normalized_name();

ALTER TABLE model_version
    ADD COLUMN artifact_attested_sha256 VARCHAR(64);

ALTER TABLE model_version
    ADD COLUMN artifact_attested_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE model_version
    ADD CONSTRAINT ck_model_version_attested_sha256
        CHECK (
            artifact_attested_sha256 IS NULL
            OR artifact_attested_sha256 ~ '^[0-9a-f]{64}$'
        );
