-- 为新计划保存标注员清单、分配规则和工作流版本；保留旧字段供兼容读取。
ALTER TABLE package_plan
    ADD COLUMN annotator_roster JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(annotator_roster) = 'array'),
    ADD COLUMN assignment_policy JSONB NOT NULL DEFAULT '{"strategy":"OPEN_POOL"}'::jsonb
        CHECK (jsonb_typeof(assignment_policy) = 'object'),
    ADD COLUMN workflow_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(workflow_snapshot) = 'object');

ALTER TABLE aggregation_job
    ADD COLUMN parameters JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(parameters) = 'object');

