-- 同项目、同批次归属通过组合外键约束，不依赖前端传入的 ID。
ALTER TABLE sample ADD CONSTRAINT uq_sample_batch UNIQUE (id, batch_id);
CREATE TABLE package_plan (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    batch_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    package_size INTEGER NOT NULL CHECK (package_size > 0),
    annotators_per_sample INTEGER NOT NULL CHECK (annotators_per_sample > 0),
    sample_count INTEGER NOT NULL CHECK (sample_count > 0),
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED')),
    selection_hash CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id, project_id) REFERENCES import_batch(id, project_id),
    FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id),
    UNIQUE (id, batch_id),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_plan_published_batch ON package_plan(batch_id) WHERE published_at IS NOT NULL;
CREATE TABLE task_package (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES package_plan(id),
    sequence_no INTEGER NOT NULL CHECK (sequence_no > 0),
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAUSED','CLOSED')),
    sample_count INTEGER NOT NULL CHECK (sample_count > 0),
    required_count INTEGER NOT NULL CHECK (required_count > 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (plan_id, sequence_no),
    UNIQUE (id, plan_id)
);
CREATE TABLE package_item (
    package_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    PRIMARY KEY (package_id, sample_id),
    UNIQUE (plan_id, sample_id),
    UNIQUE (package_id, ordinal),
    FOREIGN KEY (package_id, plan_id) REFERENCES task_package(id, plan_id),
    FOREIGN KEY (plan_id, batch_id) REFERENCES package_plan(id, batch_id),
    FOREIGN KEY (sample_id, batch_id) REFERENCES sample(id, batch_id)
);
CREATE TABLE package_slot (
    id UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES task_package(id),
    slot_no INTEGER NOT NULL CHECK (slot_no > 0),
    UNIQUE (package_id, slot_no),
    UNIQUE (id, package_id)
);
CREATE TABLE assignment (
    id UUID PRIMARY KEY,
    slot_id UUID NOT NULL,
    package_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES actor(id),
    status TEXT NOT NULL DEFAULT 'CLAIMED' CHECK (status IN ('CLAIMED','IN_PROGRESS','SUBMITTED','RELEASED','REVOKED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    release_reason TEXT,
    FOREIGN KEY (slot_id, package_id) REFERENCES package_slot(id, package_id),
    UNIQUE (id, package_id),
    CHECK ((status IN ('SUBMITTED','RELEASED','REVOKED')) = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_assignment_slot_reserved ON assignment(slot_id)
    WHERE status IN ('CLAIMED','IN_PROGRESS','SUBMITTED');
CREATE UNIQUE INDEX uq_assignment_actor_package_reserved ON assignment(package_id, actor_id)
    WHERE status IN ('CLAIMED','IN_PROGRESS','SUBMITTED');
CREATE INDEX ix_assignment_actor ON assignment(actor_id, id);
-- 发布结构不提供 UPDATE/DELETE 权限；只允许领取状态后续流转。
GRANT SELECT, INSERT ON package_plan, task_package, package_item, package_slot TO tss_labeling_app;
GRANT UPDATE (status, published_at) ON package_plan TO tss_labeling_app;
GRANT SELECT, INSERT, UPDATE ON assignment TO tss_labeling_app;
GRANT UPDATE (status, revision) ON task_package TO tss_labeling_app;
