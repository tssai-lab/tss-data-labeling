-- 草稿属于一次领取和包成员；多人答案彼此隔离。
CREATE TABLE annotation_draft (
    assignment_id UUID NOT NULL,
    package_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    result_format TEXT NOT NULL CHECK (result_format = 'image-classification-v1'),
    result JSONB NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    explicit_done BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id, sample_id),
    FOREIGN KEY (assignment_id, package_id) REFERENCES assignment(id, package_id),
    FOREIGN KEY (package_id, sample_id) REFERENCES package_item(package_id, sample_id)
);
ALTER TABLE assignment ADD CONSTRAINT uq_assignment_author UNIQUE (id, package_id, actor_id);
ALTER TABLE package_plan ADD CONSTRAINT uq_plan_spec_project UNIQUE (id, project_id, spec_version_id);
CREATE TABLE submission (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL UNIQUE,
    package_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    project_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    submitted_by UUID NOT NULL,
    content_hash CHAR(64) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (assignment_id, package_id, submitted_by) REFERENCES assignment(id, package_id, actor_id),
    FOREIGN KEY (package_id, plan_id) REFERENCES task_package(id, plan_id),
    FOREIGN KEY (plan_id, project_id, spec_version_id) REFERENCES package_plan(id, project_id, spec_version_id),
    UNIQUE (id, package_id)
);
CREATE TABLE submission_item (
    submission_id UUID NOT NULL,
    package_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    draft_revision BIGINT NOT NULL CHECK (draft_revision > 0),
    result_format TEXT NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY (submission_id, sample_id),
    FOREIGN KEY (submission_id, package_id) REFERENCES submission(id, package_id),
    FOREIGN KEY (package_id, sample_id) REFERENCES package_item(package_id, sample_id)
);
CREATE INDEX ix_submission_project ON submission(project_id, id);
GRANT SELECT, INSERT, UPDATE ON annotation_draft TO tss_labeling_app;
-- 快照仅可新增和读取，业务账号不能覆盖或删除已提交答案。
GRANT SELECT, INSERT ON submission, submission_item TO tss_labeling_app;
