-- 人工轮次绑定任务包；添加 AI 执行后转为 MIXED，仍共用冻结范围与样本占用。
ALTER TABLE publication_round DROP CONSTRAINT publication_round_mode_check;
ALTER TABLE publication_round ADD CONSTRAINT publication_round_mode_check
    CHECK (mode IN ('HUMAN_POOL', 'AI_AGENT', 'MIXED'));

ALTER TABLE publication_round DROP CONSTRAINT ck_round_human_package;
ALTER TABLE publication_round ADD CONSTRAINT ck_round_human_package
    CHECK ((mode IN ('HUMAN_POOL', 'MIXED')) = (package_id IS NOT NULL));
