-- The volatile default assigns distinct IDs to existing rows and future inserts.
ALTER TABLE submission_item ADD COLUMN result_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE submission_item ADD CONSTRAINT uq_submission_item_result UNIQUE (result_id);
CREATE INDEX ix_submission_item_sample ON submission_item(sample_id, submission_id);
CREATE INDEX ix_ai_snapshot_item_sample ON ai_result_snapshot_item(sample_id, execution_id);

-- Read immutable answers from their existing sources without copying result content.
CREATE VIEW project_result_candidate AS
SELECT i.result_id, i.sample_id, s.project_id, s.spec_version_id,
       'HUMAN'::text AS source_kind, s.submitted_by AS source_actor, s.id AS submission_id,
       s.package_id, s.assignment_id, s.round_id, NULL::uuid AS execution_id,
       NULL::text AS model_version, NULL::double precision AS confidence, i.result,
       CASE WHEN ri.action = 'MODIFY' AND ri.result IS DISTINCT FROM i.result THEN false ELSE true END AS current_version
FROM submission_item i JOIN submission s ON s.id = i.submission_id
LEFT JOIN review_item ri ON ri.submission_id = i.submission_id AND ri.sample_id = i.sample_id
UNION ALL
SELECT i.result_id, i.sample_id, e.project_id, e.spec_version_id,
       'AI', e.agent_actor_id, NULL, r.package_id, NULL, e.round_id, e.id,
       sn.model_version, i.confidence, i.result, true
FROM ai_result_snapshot_item i
JOIN ai_result_snapshot sn ON sn.id = i.snapshot_id AND sn.execution_id = i.execution_id
JOIN ai_execution e ON e.id = i.execution_id
JOIN publication_round r ON r.id = e.round_id
WHERE i.outcome = 'SUCCEEDED'
UNION ALL
SELECT a.id, a.sample_id, s.project_id, s.spec_version_id,
       'OWNER', a.actor_id, s.id, s.package_id, s.assignment_id, s.round_id, NULL,
       NULL, NULL, a.after_result,
       COALESCE(ri.action = 'MODIFY' AND ri.reviewed_at = a.created_at AND ri.result = a.after_result, false)
FROM review_audit a JOIN submission s ON s.id = a.submission_id AND s.project_id = a.project_id
LEFT JOIN review_item ri ON ri.submission_id = a.submission_id AND ri.sample_id = a.sample_id
WHERE a.action = 'REVIEW_ITEM' AND a.sample_id IS NOT NULL
  AND a.after_result IS NOT NULL AND a.after_result IS DISTINCT FROM a.before_result;

CREATE TABLE result_selection (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    spec_version_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES actor(id),
    purpose TEXT NOT NULL CHECK (purpose IN ('PUBLISH', 'REVIEW', 'FINALIZE')),
    review_action TEXT,
    filter_json JSONB,
    policy_id UUID REFERENCES confidence_policy(id),
    statistics_json JSONB NOT NULL,
    selection_hash CHAR(64) NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 minutes'),
    CHECK ((purpose = 'REVIEW' AND review_action IS NOT NULL AND review_action IN ('ACCEPT', 'NEEDS_REVISION'))
        OR (purpose <> 'REVIEW' AND review_action IS NULL)),
    FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id),
    UNIQUE (id, project_id)
);

CREATE TABLE result_selection_item (
    selection_id UUID NOT NULL,
    project_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    result_id UUID,
    review_revision BIGINT NOT NULL CHECK (review_revision >= 0),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    PRIMARY KEY (selection_id, sample_id),
    UNIQUE (selection_id, ordinal),
    FOREIGN KEY (selection_id, project_id) REFERENCES result_selection(id, project_id),
    FOREIGN KEY (sample_id, project_id) REFERENCES sample(id, project_id)
);

GRANT SELECT ON project_result_candidate TO tss_labeling_app;
GRANT SELECT, INSERT ON result_selection, result_selection_item TO tss_labeling_app;
