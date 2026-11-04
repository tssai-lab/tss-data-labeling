CREATE TABLE IF NOT EXISTS model_asset (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(64),
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS model_version (
    id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    storage_path VARCHAR(1024),
    size_bytes BIGINT,
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS model_upload_session (
    id VARCHAR(96) PRIMARY KEY,
    file_fingerprint VARCHAR(512),
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    chunk_size INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    storage_path VARCHAR(1024),
    asset_id VARCHAR(64),
    version_id VARCHAR(64),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS model_upload_chunk (
    id VARCHAR(96) PRIMARY KEY,
    upload_id VARCHAR(96) NOT NULL,
    part_index INTEGER NOT NULL,
    object_name VARCHAR(1024) NOT NULL,
    size_bytes BIGINT,
    etag VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS dataset_asset (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(64),
    cv_task_type VARCHAR(64),
    annotation_format VARCHAR(64),
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS dataset_version (
    id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    storage_path VARCHAR(1024),
    size_bytes BIGINT,
    cv_task_type VARCHAR(64),
    annotation_format VARCHAR(64),
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS dataset_upload_session (
    id VARCHAR(96) PRIMARY KEY,
    file_fingerprint VARCHAR(512),
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    chunk_size INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL,
    dataset_name VARCHAR(255) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    task_type VARCHAR(16) NOT NULL,
    cv_task_type VARCHAR(64),
    annotation_format VARCHAR(64),
    remark VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    storage_path VARCHAR(1024),
    asset_id VARCHAR(64),
    version_id VARCHAR(64),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS dataset_upload_chunk (
    id VARCHAR(96) PRIMARY KEY,
    upload_id VARCHAR(96) NOT NULL,
    part_index INTEGER NOT NULL,
    object_name VARCHAR(1024) NOT NULL,
    size_bytes BIGINT,
    etag VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS training_experiment_version (
    id VARCHAR(64) PRIMARY KEY,
    experiment_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    name VARCHAR(255),
    model_version_id VARCHAR(64),
    code_version_id VARCHAR(128) NOT NULL,
    dataset_version_id VARCHAR(64) NOT NULL,
    hyper_params_json TEXT,
    status VARCHAR(32),
    remark VARCHAR(1024),
    owner_user_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_model_upload_fingerprint ON model_upload_session (file_fingerprint);
CREATE INDEX IF NOT EXISTS idx_model_upload_status ON model_upload_session (status);
CREATE INDEX IF NOT EXISTS idx_model_upload_chunk_session ON model_upload_chunk (upload_id);

CREATE INDEX IF NOT EXISTS idx_dataset_upload_fingerprint ON dataset_upload_session (file_fingerprint);
CREATE INDEX IF NOT EXISTS idx_dataset_upload_status ON dataset_upload_session (status);
CREATE INDEX IF NOT EXISTS idx_dataset_upload_chunk_session ON dataset_upload_chunk (upload_id);

CREATE INDEX IF NOT EXISTS idx_training_experiment_id ON training_experiment_version (experiment_id);

