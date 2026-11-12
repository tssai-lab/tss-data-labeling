ALTER TABLE ai_result_snapshot_item
    ADD COLUMN result_id UUID,
    ADD COLUMN error_message TEXT;

UPDATE ai_result_snapshot_item si
SET error_message = 'AI 处理失败，请根据错误码排查或联系管理员。'
WHERE si.outcome = 'FAILED';

UPDATE ai_result_snapshot_item
SET result_id = gen_random_uuid()
WHERE outcome = 'SUCCEEDED';

ALTER TABLE ai_result_snapshot_item
    ADD CONSTRAINT ck_ai_snapshot_item_result_id
        CHECK ((outcome = 'SUCCEEDED') = (result_id IS NOT NULL));

CREATE UNIQUE INDEX uq_ai_snapshot_item_result_id
    ON ai_result_snapshot_item(result_id);
