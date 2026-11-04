CREATE TABLE IF NOT EXISTS code_asset (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    training_profile VARCHAR(128),
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS code_version (
    id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    storage_path VARCHAR(1024),
    size_bytes BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_code_version_asset_version UNIQUE (asset_id, version)
);

CREATE INDEX IF NOT EXISTS idx_code_version_asset_id ON code_version(asset_id);

ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS training_profile VARCHAR(128);
