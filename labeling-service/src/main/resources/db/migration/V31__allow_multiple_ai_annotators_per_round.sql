-- 一个 AI 轮次可以有多个独立 Agent 执行；每个执行仍有自己的租约、快照和结果。
ALTER TABLE ai_execution DROP CONSTRAINT ai_execution_round_id_key;
CREATE INDEX ix_ai_execution_round ON ai_execution(round_id, created_at, id);
