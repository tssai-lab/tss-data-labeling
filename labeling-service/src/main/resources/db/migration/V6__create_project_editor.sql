CREATE TABLE project_editor_mapping (
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES actor(id),
    spec_version_id UUID NOT NULL,
    sync_status TEXT NOT NULL CHECK (sync_status IN ('PENDING','READY','SAVED')),
    image_sha256 TEXT NOT NULL,
    external_project_id TEXT,
    external_task_id TEXT,
    external_annotation_id TEXT,
    external_author_id TEXT,
    result_hash TEXT,
    PRIMARY KEY(project_id,sample_id,actor_id,spec_version_id),
    FOREIGN KEY(sample_id,project_id) REFERENCES sample(id,project_id),
    FOREIGN KEY(spec_version_id,project_id) REFERENCES spec_version(id,project_id)
);
CREATE TABLE project_annotation (
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    result JSONB NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(project_id,sample_id,actor_id,spec_version_id),
    FOREIGN KEY(project_id,sample_id,actor_id,spec_version_id)
      REFERENCES project_editor_mapping(project_id,sample_id,actor_id,spec_version_id)
);
CREATE TABLE project_editor_session (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    launch_hash TEXT NOT NULL,
    launch_expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT false,
    cookie_hash TEXT,
    request_key VARCHAR(200) NOT NULL,
    response_body JSONB NOT NULL,
    UNIQUE(actor_id,project_id,request_key),
    FOREIGN KEY(project_id,sample_id,actor_id,spec_version_id)
      REFERENCES project_editor_mapping(project_id,sample_id,actor_id,spec_version_id)
);
GRANT SELECT, INSERT, UPDATE ON project_editor_mapping,project_annotation,project_editor_session TO tss_labeling_app;
