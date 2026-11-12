-- V1-V5 belong to the business backend. Editor integration never recreates drafts/submissions.
CREATE TABLE editor_mapping (
    assignment_id UUID NOT NULL,
    package_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    spec_version_id UUID NOT NULL REFERENCES spec_version(id),
    provider TEXT NOT NULL DEFAULT 'LABEL_STUDIO' CHECK (provider = 'LABEL_STUDIO'),
    sync_status TEXT NOT NULL CHECK (sync_status IN ('PENDING','READY','SYNCING','SAVED','FAILED')),
    image_sha256 TEXT NOT NULL,
    external_project_id TEXT,
    external_task_id TEXT,
    external_annotation_id TEXT,
    external_author_id TEXT,
    operation_id UUID,
    base_revision BIGINT CHECK (base_revision >= 0),
    pending_result JSONB,
    result_hash TEXT,
    synced_revision BIGINT CHECK (synced_revision > 0),
    PRIMARY KEY (assignment_id,sample_id,provider),
    UNIQUE (assignment_id,sample_id,provider,actor_id),
    UNIQUE (provider,external_project_id,external_task_id),
    FOREIGN KEY (assignment_id,package_id,actor_id) REFERENCES assignment(id,package_id,actor_id),
    FOREIGN KEY (package_id,sample_id) REFERENCES package_item(package_id,sample_id),
    CHECK (sync_status NOT IN ('READY','SYNCING','SAVED') OR
           (external_project_id IS NOT NULL AND external_task_id IS NOT NULL AND external_author_id IS NOT NULL))
);
CREATE INDEX ix_editor_mapping_package_sample ON editor_mapping(package_id,sample_id);
CREATE TABLE editor_session (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    provider TEXT NOT NULL DEFAULT 'LABEL_STUDIO',
    actor_id UUID NOT NULL REFERENCES actor(id),
    expires_at TIMESTAMPTZ NOT NULL,
    launch_hash TEXT NOT NULL,
    launch_expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT false,
    cookie_hash TEXT,
    request_key VARCHAR(200) NOT NULL,
    response_body JSONB NOT NULL,
    UNIQUE (actor_id,assignment_id,request_key),
    FOREIGN KEY (assignment_id,sample_id,provider,actor_id) REFERENCES editor_mapping(assignment_id,sample_id,provider,actor_id)
);
GRANT SELECT,INSERT,UPDATE ON editor_mapping,editor_session TO tss_labeling_app;
