-- AI 执行：Agent 注册、执行生命周期、逐样本输出与终结快照。
-- AI 轮次无人工包（publication_round.package_id 为空），范围由 round_item 固定，
-- 同一样本占用复用 sample_work_claim，不强制创建人工 assignment。

CREATE TABLE ai_agent_config (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES actor(id),
    task_type VARCHAR(80) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    model_version VARCHAR(120) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    supported_labels JSONB NOT NULL DEFAULT '[]'::jsonb,
    supports_reference BOOLEAN NOT NULL DEFAULT FALSE,
    confidence_description TEXT,
    -- 工作端凭据只保存 SHA-256，不落明文。
    worker_key_hash CHAR(64) NOT NULL CHECK (worker_key_hash ~ '^[0-9a-f]{64}$'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_agent_task_type CHECK (length(btrim(task_type)) > 0),
    CONSTRAINT ck_agent_caps_object CHECK (jsonb_typeof(capabilities) = 'object'),
    CONSTRAINT ck_agent_labels_array CHECK (jsonb_typeof(supported_labels) = 'array')
);

CREATE TABLE ai_execution (
    id UUID PRIMARY KEY,
    round_id UUID NOT NULL UNIQUE REFERENCES publication_round(id),
    project_id UUID NOT NULL,
    spec_version_id UUID NOT NULL,
    agent_actor_id UUID NOT NULL REFERENCES actor(id),
    model_version VARCHAR(120) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    retry_of_execution_id UUID,
    success_count INTEGER NOT NULL DEFAULT 0 CHECK (success_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    pending_count INTEGER NOT NULL DEFAULT 0 CHECK (pending_count >= 0),
    cancelled_count INTEGER NOT NULL DEFAULT 0 CHECK (cancelled_count >= 0),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, round_id),
    CONSTRAINT fk_ai_exec_round FOREIGN KEY (round_id, project_id, spec_version_id)
        REFERENCES publication_round(id, project_id, spec_version_id),
    CONSTRAINT fk_ai_exec_spec FOREIGN KEY (spec_version_id, project_id)
        REFERENCES spec_version(id, project_id),
    CONSTRAINT fk_ai_exec_retry FOREIGN KEY (retry_of_execution_id) REFERENCES ai_execution(id)
);

-- 每个输入样本一行；成功必填结果，失败不填虚假答案。
CREATE TABLE ai_item_output (
    execution_id UUID NOT NULL,
    round_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED')),
    result JSONB,
    confidence DOUBLE PRECISION,
    error_code TEXT,
    error_message TEXT,
    generation BIGINT NOT NULL DEFAULT 1,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    PRIMARY KEY (execution_id, sample_id),
    CONSTRAINT fk_ai_item_exec FOREIGN KEY (execution_id, round_id) REFERENCES ai_execution(id, round_id),
    CONSTRAINT fk_ai_item_sample FOREIGN KEY (round_id, sample_id) REFERENCES round_item(round_id, sample_id),
    CONSTRAINT ck_ai_item_result CHECK ((status = 'SUCCEEDED') = (result IS NOT NULL AND jsonb_typeof(result) = 'object')),
    CONSTRAINT ck_ai_item_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE TABLE ai_result_snapshot (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    model_version VARCHAR(120) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    success_count INTEGER NOT NULL CHECK (success_count >= 0),
    failed_count INTEGER NOT NULL CHECK (failed_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, execution_id),
    CONSTRAINT fk_ai_snapshot_exec FOREIGN KEY (execution_id) REFERENCES ai_execution(id)
);

CREATE TABLE ai_result_snapshot_item (
    snapshot_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCEEDED','FAILED','CANCELLED')),
    result JSONB,
    confidence DOUBLE PRECISION,
    error_code TEXT,
    PRIMARY KEY (snapshot_id, sample_id),
    CONSTRAINT fk_ai_snapshot_item FOREIGN KEY (snapshot_id, execution_id)
        REFERENCES ai_result_snapshot(id, execution_id),
    CONSTRAINT fk_ai_snapshot_item_output FOREIGN KEY (execution_id, sample_id)
        REFERENCES ai_item_output(execution_id, sample_id)
);

CREATE INDEX ix_ai_exec_project ON ai_execution(project_id, id);
CREATE INDEX ix_ai_exec_queue ON ai_execution(created_at, id) WHERE status IN ('QUEUED','RUNNING');
-- AI 轮次按项目递增 round_no（人工轮次按包递增）。
CREATE UNIQUE INDEX uq_round_ai_no ON publication_round(project_id, round_no) WHERE package_id IS NULL;

GRANT SELECT ON ai_agent_config TO tss_labeling_app;
GRANT SELECT, INSERT, UPDATE ON ai_execution, ai_item_output, ai_result_snapshot, ai_result_snapshot_item TO tss_labeling_app;
