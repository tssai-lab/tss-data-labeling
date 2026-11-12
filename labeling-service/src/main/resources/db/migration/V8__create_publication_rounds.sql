-- 多轮人工任务：引入发布轮次，解除“同一批次只能发布一次”的旧限制，
-- 使需求方能按任意样本范围再次发布，并用样本占用防止同一时间重复处理。
-- 本迁移支持已有数据：新增列先回填再设 NOT NULL，并为每个已有包生成默认轮次。

-- 1) 解除包计划对单一批次的硬依赖。
DROP INDEX uq_plan_published_batch;
ALTER TABLE package_plan DROP CONSTRAINT package_plan_batch_id_project_id_fkey;
ALTER TABLE package_plan ALTER COLUMN batch_id DROP NOT NULL;
-- 再次发布按冻结的样本清单规划，不再依赖 batch；与 batch 模式互斥。
ALTER TABLE package_plan ADD COLUMN selection_json JSONB;
ALTER TABLE package_plan ADD COLUMN instructions TEXT;
ALTER TABLE package_plan ADD CONSTRAINT ck_plan_selection_json
    CHECK (selection_json IS NULL OR jsonb_typeof(selection_json) = 'array');
ALTER TABLE package_plan ADD CONSTRAINT ck_plan_source
    CHECK ((batch_id IS NOT NULL) <> (selection_json IS NOT NULL));

-- 2) 发布轮次：人工轮次必须绑定包，AI 轮次必须无包（本轮仅实现人工）。
CREATE TABLE publication_round (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    spec_version_id UUID NOT NULL,
    package_id UUID,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    mode TEXT NOT NULL CHECK (mode IN ('HUMAN_POOL', 'AI_AGENT')),
    instructions TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_round_spec_project FOREIGN KEY (spec_version_id, project_id)
        REFERENCES spec_version(id, project_id),
    CONSTRAINT fk_round_package FOREIGN KEY (package_id) REFERENCES task_package(id),
    CONSTRAINT ck_round_human_package CHECK ((mode = 'HUMAN_POOL') = (package_id IS NOT NULL)),
    CONSTRAINT uq_round_package_no UNIQUE (package_id, round_no),
    CONSTRAINT uq_round_package UNIQUE (id, package_id),
    CONSTRAINT uq_round_project_spec UNIQUE (id, project_id, spec_version_id)
);
CREATE UNIQUE INDEX uq_round_open_per_package ON publication_round(package_id)
    WHERE status = 'OPEN' AND package_id IS NOT NULL;

-- 3) 轮次冻结范围与引用结果。
CREATE TABLE round_item (
    round_id UUID NOT NULL,
    project_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    reference_result_id UUID,
    PRIMARY KEY (round_id, sample_id),
    CONSTRAINT uq_round_item_ordinal UNIQUE (round_id, ordinal),
    CONSTRAINT fk_round_item_round FOREIGN KEY (round_id, project_id, spec_version_id)
        REFERENCES publication_round(id, project_id, spec_version_id),
    CONSTRAINT fk_round_item_sample FOREIGN KEY (sample_id, project_id)
        REFERENCES sample(id, project_id)
);

-- 4) 样本占用：同一时间同一样本只能属于一个 OPEN 轮次；轮次结束后删除占用行。
CREATE TABLE sample_work_claim (
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    round_id UUID NOT NULL REFERENCES publication_round(id),
    PRIMARY KEY (project_id, sample_id),
    CONSTRAINT fk_claim_sample FOREIGN KEY (sample_id, project_id)
        REFERENCES sample(id, project_id)
);

-- 5) 包成员不再绑定单一批次，改为绑定项目，样本可跨批次。先回填再设 NOT NULL。
ALTER TABLE package_item DROP CONSTRAINT package_item_sample_id_batch_id_fkey;
ALTER TABLE package_item DROP CONSTRAINT package_item_plan_id_batch_id_fkey;
ALTER TABLE package_item DROP COLUMN batch_id;
ALTER TABLE package_item ADD COLUMN project_id UUID;
UPDATE package_item pi SET project_id = pp.project_id
FROM package_plan pp WHERE pp.id = pi.plan_id;
ALTER TABLE package_item ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE package_item ADD CONSTRAINT package_item_sample_project_fkey
    FOREIGN KEY (sample_id, project_id) REFERENCES sample(id, project_id);

-- 6) 为已有包生成默认轮次（每包一轮，round_no=1），并回填轮次成员与占用。
INSERT INTO publication_round (id, project_id, spec_version_id, package_id, round_no, mode, status, revision)
SELECT gen_random_uuid(), pp.project_id, pp.spec_version_id, tp.id, 1, 'HUMAN_POOL',
       CASE WHEN tp.status = 'OPEN' THEN 'OPEN' ELSE 'CLOSED' END, 1
FROM task_package tp JOIN package_plan pp ON pp.id = tp.plan_id
WHERE NOT EXISTS (SELECT 1 FROM publication_round r WHERE r.package_id = tp.id);

INSERT INTO round_item (round_id, project_id, spec_version_id, sample_id, ordinal)
SELECT r.id, r.project_id, r.spec_version_id, pi.sample_id, pi.ordinal
FROM package_item pi JOIN publication_round r ON r.package_id = pi.package_id
WHERE NOT EXISTS (SELECT 1 FROM round_item ri WHERE ri.round_id = r.id AND ri.sample_id = pi.sample_id);

INSERT INTO sample_work_claim (project_id, sample_id, round_id)
SELECT r.project_id, ri.sample_id, r.id
FROM publication_round r JOIN round_item ri ON ri.round_id = r.id
WHERE r.status = 'OPEN' AND r.mode = 'HUMAN_POOL'
ON CONFLICT (project_id, sample_id) DO NOTHING;

-- 7) 席位改为按轮次分配：先加列回填，再设 NOT NULL 并调整约束。
ALTER TABLE package_slot ADD COLUMN round_id UUID;
UPDATE package_slot ps SET round_id = r.id
FROM publication_round r WHERE r.package_id = ps.package_id;
ALTER TABLE package_slot ALTER COLUMN round_id SET NOT NULL;
ALTER TABLE package_slot DROP CONSTRAINT package_slot_package_id_slot_no_key;
ALTER TABLE package_slot ADD CONSTRAINT package_slot_round_package_fkey
    FOREIGN KEY (round_id, package_id) REFERENCES publication_round(id, package_id);
ALTER TABLE package_slot ADD CONSTRAINT package_slot_round_slot_no_key UNIQUE (round_id, slot_no);
ALTER TABLE package_slot ADD CONSTRAINT package_slot_id_round_package_key UNIQUE (id, round_id, package_id);

-- 8) 提交快照先解除旧作者外键（它依赖 assignment 的旧唯一键 uq_assignment_author）。
ALTER TABLE submission DROP CONSTRAINT submission_assignment_id_package_id_submitted_by_fkey;

-- 9) 领取记录绑定轮次：先加列回填，再设 NOT NULL 并调整约束。
ALTER TABLE assignment ADD COLUMN round_id UUID;
UPDATE assignment a SET round_id = ps.round_id
FROM package_slot ps WHERE ps.id = a.slot_id;
ALTER TABLE assignment ALTER COLUMN round_id SET NOT NULL;

-- 旧版提交不会关闭 task_package。若包内所有席位已有正式提交，迁移时关闭其默认轮次，
-- 否则 OPEN 轮次会占用样本并阻止该包后续再次发布。
UPDATE publication_round r SET status = 'CLOSED', revision = revision + 1
WHERE r.status = 'OPEN'
  AND r.package_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM package_slot ps WHERE ps.round_id = r.id)
  AND NOT EXISTS (
      SELECT 1 FROM package_slot ps
      WHERE ps.round_id = r.id
        AND NOT EXISTS (
            SELECT 1 FROM assignment a
            WHERE a.slot_id = ps.id AND a.status = 'SUBMITTED'
        )
  );
DELETE FROM sample_work_claim c
USING publication_round r
WHERE c.round_id = r.id AND r.status <> 'OPEN';

-- 编辑器映射保留为历史数据，随 assignment 一起绑定到新的轮次唯一键。
ALTER TABLE editor_mapping ADD COLUMN round_id UUID;
UPDATE editor_mapping em SET round_id = a.round_id
FROM assignment a WHERE a.id = em.assignment_id;
ALTER TABLE editor_mapping ALTER COLUMN round_id SET NOT NULL;
ALTER TABLE editor_mapping DROP CONSTRAINT editor_mapping_assignment_id_package_id_actor_id_fkey;

DROP INDEX uq_assignment_actor_package_reserved;
ALTER TABLE assignment DROP CONSTRAINT uq_assignment_author;
ALTER TABLE assignment DROP CONSTRAINT assignment_slot_id_package_id_fkey;
ALTER TABLE assignment ADD CONSTRAINT assignment_slot_round_package_fkey
    FOREIGN KEY (slot_id, round_id, package_id) REFERENCES package_slot(id, round_id, package_id);
ALTER TABLE assignment ADD CONSTRAINT assignment_round_package_fkey
    FOREIGN KEY (round_id, package_id) REFERENCES publication_round(id, package_id);
ALTER TABLE assignment ADD CONSTRAINT uq_assignment_author_round UNIQUE (id, round_id, package_id, actor_id);
ALTER TABLE editor_mapping ADD CONSTRAINT editor_mapping_assignment_round_package_actor_fkey
    FOREIGN KEY (assignment_id, round_id, package_id, actor_id)
    REFERENCES assignment(id, round_id, package_id, actor_id);
CREATE UNIQUE INDEX uq_assignment_actor_round_reserved ON assignment(round_id, actor_id)
    WHERE status IN ('CLAIMED', 'IN_PROGRESS', 'SUBMITTED');

-- 10) 提交快照绑定轮次：先加列回填，再设 NOT NULL 并加约束。
ALTER TABLE submission ADD COLUMN round_id UUID;
UPDATE submission s SET round_id = a.round_id
FROM assignment a WHERE a.id = s.assignment_id;
ALTER TABLE submission ALTER COLUMN round_id SET NOT NULL;
ALTER TABLE submission ADD CONSTRAINT submission_round_package_fkey
    FOREIGN KEY (round_id, package_id) REFERENCES publication_round(id, package_id);
ALTER TABLE submission ADD CONSTRAINT submission_assignment_round_package_author_fkey
    FOREIGN KEY (assignment_id, round_id, package_id, submitted_by)
    REFERENCES assignment(id, round_id, package_id, actor_id);

GRANT SELECT, INSERT, UPDATE ON publication_round TO tss_labeling_app;
GRANT SELECT, INSERT ON round_item TO tss_labeling_app;
GRANT SELECT, INSERT, DELETE ON sample_work_claim TO tss_labeling_app;
