CREATE TABLE IF NOT EXISTS inference_script_asset (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS inference_script_version (
    id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    storage_path VARCHAR(1024),
    size_bytes BIGINT,
    runtime VARCHAR(32) NOT NULL,
    entry_file VARCHAR(512) NOT NULL,
    params_schema_json TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_inference_script_version_asset_version UNIQUE (asset_id, version)
);

CREATE TABLE IF NOT EXISTS inference_task (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255),
    model_version_id VARCHAR(64) NOT NULL,
    script_version_id VARCHAR(64) NOT NULL,
    input_mode VARCHAR(32) NOT NULL,
    dataset_version_id VARCHAR(64),
    input_object_name VARCHAR(1024),
    params_json TEXT,
    status VARCHAR(32),
    progress INTEGER,
    result_json TEXT,
    log_path VARCHAR(1024),
    output_path VARCHAR(1024),
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_inference_script_version_asset_id
    ON inference_script_version(asset_id);

CREATE INDEX IF NOT EXISTS idx_inference_script_version_owner
    ON inference_script_version(owner_user_id);

CREATE INDEX IF NOT EXISTS idx_inference_task_owner_created
    ON inference_task(owner_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_inference_task_status
    ON inference_task(status);

CREATE INDEX IF NOT EXISTS idx_inference_task_model_version
    ON inference_task(model_version_id);

CREATE INDEX IF NOT EXISTS idx_inference_task_dataset_version
    ON inference_task(dataset_version_id);
