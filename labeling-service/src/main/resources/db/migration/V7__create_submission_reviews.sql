-- 需求方审核：按提交逐样本作出接受/修改/退回/待修正，并保留只增审计记录。
-- 提交本体（submission/submission_item）保持不可变，审核结论不覆盖标注员答案。
CREATE TABLE review_item (
    submission_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('ACCEPT', 'MODIFY', 'REJECT', 'CORRECTION')),
    result_format TEXT,
    result JSONB,
    reason TEXT,
    reviewed_by UUID NOT NULL REFERENCES actor(id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (submission_id, sample_id),
    FOREIGN KEY (submission_id, sample_id) REFERENCES submission_item(submission_id, sample_id),
    -- 只有修改会写入新结果；接受表示沿用冻结答案，退回/待修正不产生新结果。
    CHECK (action = 'MODIFY' OR (result IS NULL AND result_format IS NULL)),
    CHECK (action <> 'MODIFY' OR (result IS NOT NULL AND result_format IS NOT NULL AND jsonb_typeof(result) = 'object'))
);

CREATE TABLE review_audit (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    submission_id UUID NOT NULL REFERENCES submission(id),
    actor_id UUID NOT NULL REFERENCES actor(id),
    action TEXT NOT NULL,
    sample_id UUID,
    before_result JSONB,
    after_result JSONB,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_review_item_submission ON review_item(submission_id, sample_id);
CREATE INDEX ix_review_audit_submission ON review_audit(submission_id, id);

GRANT SELECT, INSERT, UPDATE ON review_item TO tss_labeling_app;
-- 审计只增不删，业务账号不能覆盖或删除历史记录。
GRANT SELECT, INSERT ON review_audit TO tss_labeling_app;
