-- Additive workflow capabilities; never change historical Flyway checksums.
ALTER TABLE project ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE TABLE project_event (
 id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES project(id), actor_id UUID NOT NULL REFERENCES actor(id),
 action TEXT NOT NULL, reason TEXT NOT NULL, before_revision BIGINT NOT NULL, after_revision BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE owner_result (
 id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES project(id), spec_version_id UUID NOT NULL,
 sample_id UUID NOT NULL, parent_result_id UUID, source_kind TEXT NOT NULL CHECK(source_kind IN ('OWNER','AGGREGATION')),
 created_by UUID NOT NULL REFERENCES actor(id), result JSONB NOT NULL CHECK(jsonb_typeof(result)='object'),
 reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(spec_version_id,project_id) REFERENCES spec_version(id,project_id),
 FOREIGN KEY(sample_id,project_id) REFERENCES sample(id,project_id)
);
ALTER TABLE sample_review ADD COLUMN result_id UUID;
ALTER TABLE review_event ADD COLUMN result_id UUID;
ALTER TABLE final_snapshot_item ADD COLUMN result_id UUID;
ALTER TABLE sample_review DROP CONSTRAINT sample_review_source_kind_check;
ALTER TABLE sample_review DROP CONSTRAINT ck_review_human;
ALTER TABLE sample_review DROP CONSTRAINT ck_review_ai;
ALTER TABLE sample_review ADD CONSTRAINT ck_review_source CHECK (
 (source_kind IS NULL AND submission_id IS NULL AND execution_id IS NULL AND result_id IS NULL) OR
 (source_kind='HUMAN' AND submission_id IS NOT NULL AND execution_id IS NULL) OR
 (source_kind='AI' AND execution_id IS NOT NULL AND submission_id IS NULL) OR
 (source_kind IN ('OWNER','AGGREGATION') AND result_id IS NOT NULL AND submission_id IS NULL AND execution_id IS NULL));
ALTER TABLE final_snapshot_item DROP CONSTRAINT final_snapshot_item_source_kind_check;
ALTER TABLE final_snapshot_item DROP CONSTRAINT ck_snapshot_item_human;
ALTER TABLE final_snapshot_item DROP CONSTRAINT ck_snapshot_item_ai;
ALTER TABLE final_snapshot_item ADD CONSTRAINT ck_snapshot_source CHECK (
 (source_kind='HUMAN' AND submission_id IS NOT NULL AND execution_id IS NULL) OR
 (source_kind='AI' AND execution_id IS NOT NULL AND submission_id IS NULL) OR
 (source_kind IN ('OWNER','AGGREGATION') AND result_id IS NOT NULL AND submission_id IS NULL AND execution_id IS NULL));
ALTER TABLE package_plan ADD COLUMN selection_id UUID REFERENCES result_selection(id);
ALTER TABLE package_plan ADD COLUMN selection_consumed_at TIMESTAMPTZ;
ALTER TABLE round_item ADD COLUMN reference_candidate_id UUID;
CREATE OR REPLACE VIEW project_result_candidate AS
SELECT c.result_id,c.sample_id,c.project_id,c.spec_version_id,c.source_kind,c.source_actor,
 c.submission_id,c.package_id,c.assignment_id,c.round_id,c.execution_id,c.model_version,c.confidence,c.result,
 (c.current_version AND NOT EXISTS(SELECT 1 FROM owner_result child WHERE child.parent_result_id=c.result_id)) AS current_version
FROM (
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
  AND a.after_result IS NOT NULL AND a.after_result IS DISTINCT FROM a.before_result
UNION ALL
SELECT o.id,o.sample_id,o.project_id,o.spec_version_id,o.source_kind,o.created_by,
 NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid,NULL::text,NULL::double precision,o.result,true
FROM owner_result o
) c;
CREATE INDEX ix_owner_result_parent ON owner_result(parent_result_id);

CREATE TABLE aggregation_job (
 id UUID PRIMARY KEY,project_id UUID NOT NULL REFERENCES project(id),spec_version_id UUID NOT NULL,
 algorithm TEXT NOT NULL, input_json JSONB NOT NULL, created_by UUID NOT NULL REFERENCES actor(id),
 status TEXT NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(spec_version_id,project_id) REFERENCES spec_version(id,project_id)
);
CREATE TABLE aggregation_item (
 aggregation_id UUID NOT NULL REFERENCES aggregation_job(id),sample_id UUID NOT NULL REFERENCES sample(id),
 result_id UUID REFERENCES owner_result(id),outcome TEXT NOT NULL CHECK(outcome IN ('SUGGESTED','TIE','INSUFFICIENT_INPUT')),
 votes JSONB NOT NULL,PRIMARY KEY(aggregation_id,sample_id)
);
CREATE TABLE export_job (
 id UUID PRIMARY KEY,project_id UUID NOT NULL REFERENCES project(id),created_by UUID NOT NULL REFERENCES actor(id),
 format TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('QUEUED','SUCCEEDED','FAILED')),
 snapshot_ids JSONB NOT NULL,archive BYTEA,sha256 CHAR(64),error_code TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP+INTERVAL '7 days')
);
CREATE INDEX ix_owner_result_scope ON owner_result(project_id,spec_version_id,sample_id);
GRANT SELECT,INSERT ON owner_result,aggregation_job,aggregation_item,project_event TO tss_labeling_app;
GRANT SELECT,INSERT,UPDATE ON export_job TO tss_labeling_app;
GRANT SELECT,UPDATE ON result_selection TO tss_labeling_app;

GRANT UPDATE(selection_id,selection_consumed_at) ON package_plan TO tss_labeling_app;

GRANT INSERT ON ai_agent_config TO tss_labeling_app;

CREATE INDEX ix_receipt_actor_time ON idempotency_record(actor_id,created_at,operation,idempotency_key) WHERE response_body IS NOT NULL;
CREATE INDEX ix_export_queue ON export_job(created_at,id) WHERE status='QUEUED';
CREATE INDEX ix_round_project_page ON publication_round(project_id,id);
CREATE INDEX ix_import_project_page ON import_batch(project_id,id);
CREATE INDEX ix_plan_project_page ON package_plan(project_id,id);

ALTER TABLE package_plan ADD COLUMN reopen_accepted BOOLEAN NOT NULL DEFAULT TRUE;
GRANT UPDATE(reopen_accepted) ON package_plan TO tss_labeling_app;
