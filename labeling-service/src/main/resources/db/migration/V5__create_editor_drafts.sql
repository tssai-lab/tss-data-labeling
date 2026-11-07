CREATE TABLE annotation_draft (
    assignment_id UUID NOT NULL,
    package_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    spec_version_id UUID NOT NULL REFERENCES spec_version(id),
    result_format TEXT NOT NULL CHECK (result_format='label-studio-json'),
    result JSONB NOT NULL,
    explicit_done BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id,sample_id),
    FOREIGN KEY (assignment_id,package_id) REFERENCES assignment(id,package_id),
    FOREIGN KEY (package_id,sample_id) REFERENCES package_item(package_id,sample_id)
);
CREATE TABLE editor_mapping (
    assignment_id UUID NOT NULL,
    package_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES actor(id),
    spec_version_id UUID NOT NULL REFERENCES spec_version(id),
    provider TEXT NOT NULL DEFAULT 'LABEL_STUDIO',
    external_project_id TEXT,
    external_task_id TEXT,
    external_annotation_id TEXT,
    external_author_id TEXT,
    sync_status TEXT NOT NULL CHECK (sync_status IN ('PENDING','READY','SAVED')),
    image_sha256 TEXT NOT NULL,
    result_hash TEXT,
    base_revision BIGINT,
    PRIMARY KEY (assignment_id,sample_id),
    FOREIGN KEY (assignment_id,package_id) REFERENCES assignment(id,package_id),
    FOREIGN KEY (package_id,sample_id) REFERENCES package_item(package_id,sample_id)
);
CREATE TABLE editor_session (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES actor(id),
    expires_at TIMESTAMPTZ NOT NULL,
    launch_hash TEXT NOT NULL,
    launch_expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT false,
    cookie_hash TEXT,
    request_key VARCHAR(200) NOT NULL,
    response_body JSONB NOT NULL,
    UNIQUE (actor_id,assignment_id,request_key),
    FOREIGN KEY (assignment_id,sample_id) REFERENCES editor_mapping(assignment_id,sample_id)
);
GRANT SELECT, INSERT, UPDATE ON annotation_draft, editor_mapping TO tss_labeling_app;
GRANT SELECT, INSERT, UPDATE ON editor_session TO tss_labeling_app;
