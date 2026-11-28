-- 固化每次标注结果的提供方身份，避免后续注册版本变化影响历史溯源。
ALTER TABLE submission
    ADD COLUMN annotator_provider_id TEXT NOT NULL DEFAULT 'human-pool',
    ADD COLUMN annotator_provider_version TEXT NOT NULL DEFAULT '1.0.0',
    ADD COLUMN annotator_type TEXT NOT NULL DEFAULT 'HUMAN';

ALTER TABLE ai_execution
    ADD COLUMN annotator_provider_id TEXT NOT NULL DEFAULT 'ai-agent-worker',
    ADD COLUMN annotator_provider_version TEXT NOT NULL DEFAULT '1.0.0',
    ADD COLUMN annotator_type TEXT NOT NULL DEFAULT 'MODEL';

ALTER TABLE final_snapshot_item
    ADD COLUMN annotator_provider_id TEXT,
    ADD COLUMN annotator_provider_version TEXT,
    ADD COLUMN annotator_type TEXT,
    ADD COLUMN source_actor_id UUID REFERENCES actor(id),
    ADD COLUMN source_actor_name TEXT,
    ADD COLUMN model_version TEXT;

UPDATE final_snapshot_item f SET
    annotator_provider_id = s.annotator_provider_id,
    annotator_provider_version = s.annotator_provider_version,
    annotator_type = s.annotator_type
FROM submission s WHERE f.submission_id = s.id;

UPDATE final_snapshot_item f SET
    annotator_provider_id = e.annotator_provider_id,
    annotator_provider_version = e.annotator_provider_version,
    annotator_type = e.annotator_type
FROM ai_execution e WHERE f.execution_id = e.id;

UPDATE final_snapshot_item f SET
    source_actor_id = s.submitted_by,
    source_actor_name = a.display_name
FROM submission s JOIN actor a ON a.id = s.submitted_by
WHERE f.submission_id = s.id;

UPDATE final_snapshot_item f SET
    source_actor_id = e.agent_actor_id,
    source_actor_name = a.display_name,
    model_version = e.model_version
FROM ai_execution e JOIN actor a ON a.id = e.agent_actor_id
WHERE f.execution_id = e.id;

UPDATE final_snapshot_item f SET
    source_actor_id = r.actor_id,
    source_actor_name = a.display_name
FROM review_audit r JOIN actor a ON a.id = r.actor_id
WHERE f.result_id = r.id AND f.source_kind = 'OWNER';

UPDATE final_snapshot_item f SET
    source_actor_id = r.created_by,
    source_actor_name = a.display_name
FROM owner_result r JOIN actor a ON a.id = r.created_by
WHERE f.result_id = r.id;

CREATE OR REPLACE VIEW project_result_candidate AS
SELECT c.result_id,c.sample_id,c.project_id,c.spec_version_id,c.source_kind,c.source_actor,
 c.submission_id,c.package_id,c.assignment_id,c.round_id,c.execution_id,c.model_version,c.confidence,c.result,
 (c.current_version AND NOT EXISTS(SELECT 1 FROM owner_result child WHERE child.parent_result_id=c.result_id)) AS current_version,
 c.annotator_provider_id,c.annotator_provider_version,c.annotator_type,a.display_name AS source_actor_name
FROM (
SELECT i.result_id, i.sample_id, s.project_id, s.spec_version_id,
       'HUMAN'::text AS source_kind, s.submitted_by AS source_actor, s.id AS submission_id,
       s.package_id, s.assignment_id, s.round_id, NULL::uuid AS execution_id,
       NULL::text AS model_version, NULL::double precision AS confidence, i.result,
       CASE WHEN ri.action = 'MODIFY' AND ri.result IS DISTINCT FROM i.result THEN false ELSE true END AS current_version,
       s.annotator_provider_id,s.annotator_provider_version,s.annotator_type
FROM submission_item i JOIN submission s ON s.id = i.submission_id
LEFT JOIN review_item ri ON ri.submission_id = i.submission_id AND ri.sample_id = i.sample_id
UNION ALL
SELECT i.result_id, i.sample_id, e.project_id, e.spec_version_id,
       'AI', e.agent_actor_id, NULL, r.package_id, NULL, e.round_id, e.id,
       sn.model_version, i.confidence, i.result, true,
       e.annotator_provider_id,e.annotator_provider_version,e.annotator_type
FROM ai_result_snapshot_item i
JOIN ai_result_snapshot sn ON sn.id = i.snapshot_id AND sn.execution_id = i.execution_id
JOIN ai_execution e ON e.id = i.execution_id
JOIN publication_round r ON r.id = e.round_id
WHERE i.outcome = 'SUCCEEDED'
UNION ALL
SELECT a.id, a.sample_id, s.project_id, s.spec_version_id,
       'OWNER', a.actor_id, s.id, s.package_id, s.assignment_id, s.round_id, NULL,
       NULL, NULL, a.after_result,
       COALESCE(ri.action = 'MODIFY' AND ri.reviewed_at = a.created_at AND ri.result = a.after_result, false),
       NULL::text,NULL::text,NULL::text
FROM review_audit a JOIN submission s ON s.id = a.submission_id AND s.project_id = a.project_id
LEFT JOIN review_item ri ON ri.submission_id = a.submission_id AND ri.sample_id = a.sample_id
WHERE a.action = 'REVIEW_ITEM' AND a.sample_id IS NOT NULL
  AND a.after_result IS NOT NULL AND a.after_result IS DISTINCT FROM a.before_result
UNION ALL
SELECT o.id,o.sample_id,o.project_id,o.spec_version_id,o.source_kind,o.created_by,
 NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid,NULL::text,NULL::double precision,o.result,true,
 NULL::text,NULL::text,NULL::text
FROM owner_result o
) c
LEFT JOIN actor a ON a.id=c.source_actor;

GRANT SELECT ON project_result_candidate TO tss_labeling_app;
