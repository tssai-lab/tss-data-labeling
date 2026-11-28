-- 在发布计划中固定人工/AI组成，并允许纯 AI 计划不创建人工席位。
ALTER TABLE package_plan
    ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'HUMAN_POOL',
    ADD COLUMN ai_agent_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_plan_ai_agents_array CHECK (jsonb_typeof(ai_agent_ids) = 'array'),
    ADD CONSTRAINT ck_plan_execution_mode CHECK (execution_mode IN ('HUMAN_POOL', 'AI_AGENT', 'MIXED')),
    ADD CONSTRAINT ck_plan_human_count CHECK ((execution_mode IN ('HUMAN_POOL', 'MIXED')) = (annotators_per_sample > 0)),
    ADD CONSTRAINT ck_plan_ai_count CHECK ((execution_mode IN ('AI_AGENT', 'MIXED')) = (jsonb_array_length(ai_agent_ids) > 0));

ALTER TABLE package_plan DROP CONSTRAINT package_plan_annotators_per_sample_check;
ALTER TABLE package_plan ADD CONSTRAINT package_plan_annotators_per_sample_check
    CHECK (annotators_per_sample >= 0);

ALTER TABLE publication_round
    ADD COLUMN package_plan_id UUID REFERENCES package_plan(id);

UPDATE publication_round r SET package_plan_id = t.plan_id
FROM task_package t WHERE t.id = r.package_id;
