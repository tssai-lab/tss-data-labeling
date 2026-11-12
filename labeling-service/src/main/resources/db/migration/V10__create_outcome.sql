-- 审核后的成果：置信度分档、统一逐样本审核状态、审核事件与最终成果版本。
-- sample_review 是跨来源（人工/AI）的逐样本审核指针；最终成果只固化已确认结果。

CREATE TABLE confidence_policy (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    round_id UUID NOT NULL REFERENCES publication_round(id),
    execution_id UUID NOT NULL REFERENCES ai_execution(id),
    snapshot_id UUID NOT NULL REFERENCES ai_result_snapshot(id),
    model_version VARCHAR(120) NOT NULL,
    lower_threshold DOUBLE PRECISION NOT NULL,
    upper_threshold DOUBLE PRECISION NOT NULL,
    created_by UUID NOT NULL REFERENCES actor(id),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (lower_threshold >= 0 AND lower_threshold < upper_threshold AND upper_threshold <= 1)
);
CREATE INDEX ix_confidence_policy_exec ON confidence_policy(execution_id, created_at, id);

CREATE TABLE sample_review (
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'UNREVIEWED'
        CHECK (status IN ('UNREVIEWED','PENDING','NEEDS_REVISION','ACCEPTED')),
    source_kind TEXT CHECK (source_kind IN ('HUMAN','AI')),
    submission_id UUID,
    execution_id UUID,
    reviewed_by UUID REFERENCES actor(id),
    reason TEXT,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, sample_id, spec_version_id),
    CONSTRAINT fk_review_sample FOREIGN KEY (sample_id, project_id) REFERENCES sample(id, project_id),
    CONSTRAINT fk_review_spec FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id),
    -- 已确认必须有结果来源；来源互斥且与 source_kind 一致。
    CONSTRAINT ck_review_accepted CHECK ((status = 'ACCEPTED') = (source_kind IS NOT NULL)),
    CONSTRAINT ck_review_human CHECK ((source_kind = 'HUMAN') = (submission_id IS NOT NULL AND execution_id IS NULL)),
    CONSTRAINT ck_review_ai CHECK ((source_kind = 'AI') = (execution_id IS NOT NULL AND submission_id IS NULL))
);

CREATE TABLE review_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    sample_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES actor(id),
    action TEXT NOT NULL,
    source_kind TEXT,
    submission_id UUID,
    execution_id UUID,
    reason TEXT,
    policy_id UUID,
    before_revision BIGINT NOT NULL,
    after_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_review_event_sample FOREIGN KEY (sample_id, project_id) REFERENCES sample(id, project_id),
    CONSTRAINT fk_review_event_spec FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id)
);
CREATE INDEX ix_review_event_sample ON review_event(project_id, sample_id, id);

CREATE TABLE final_snapshot (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    spec_version_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    scope_hash CHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES actor(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, project_id),
    CONSTRAINT fk_snapshot_spec FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id),
    CONSTRAINT ck_snapshot_name CHECK (length(btrim(name)) > 0)
);
CREATE INDEX ix_final_snapshot_project ON final_snapshot(project_id, created_at, id);

CREATE TABLE final_snapshot_item (
    snapshot_id UUID NOT NULL,
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('HUMAN','AI')),
    submission_id UUID,
    execution_id UUID,
    result JSONB NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    review_revision BIGINT NOT NULL,
    source_path TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    PRIMARY KEY (snapshot_id, sample_id),
    CONSTRAINT fk_snapshot_item FOREIGN KEY (snapshot_id, project_id) REFERENCES final_snapshot(id, project_id),
    CONSTRAINT fk_snapshot_item_sample FOREIGN KEY (sample_id, project_id) REFERENCES sample(id, project_id),
    CONSTRAINT ck_snapshot_item_human CHECK ((source_kind = 'HUMAN') = (submission_id IS NOT NULL AND execution_id IS NULL)),
    CONSTRAINT ck_snapshot_item_ai CHECK ((source_kind = 'AI') = (execution_id IS NOT NULL AND submission_id IS NULL))
);

GRANT SELECT, INSERT, UPDATE ON confidence_policy, sample_review TO tss_labeling_app;
GRANT SELECT, INSERT ON review_event TO tss_labeling_app;
GRANT SELECT, INSERT ON final_snapshot, final_snapshot_item TO tss_labeling_app;
