-- Each source owns its own claim. Only the atomic publication service may
-- create overlapping claims, and only within one publication group.
CREATE TABLE publication_group (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    spec_version_id UUID NOT NULL,
    selection_id UUID NOT NULL REFERENCES result_selection(id),
    created_by UUID NOT NULL REFERENCES actor(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (spec_version_id, project_id) REFERENCES spec_version(id, project_id)
);
ALTER TABLE publication_round ADD COLUMN publication_group_id UUID REFERENCES publication_group(id);
CREATE TABLE publication_group_execution (
    publication_group_id UUID NOT NULL REFERENCES publication_group(id),
    execution_id UUID NOT NULL REFERENCES ai_execution(id),
    agent_id UUID NOT NULL REFERENCES ai_agent_config(id),
    PRIMARY KEY (publication_group_id, execution_id)
);
ALTER TABLE sample_work_claim DROP CONSTRAINT sample_work_claim_pkey;
ALTER TABLE sample_work_claim ADD PRIMARY KEY (project_id, sample_id, round_id);
-- Keep the exclusivity invariant in the database too, including legacy writers.
CREATE FUNCTION validate_parallel_sample_claim() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_project UUID; source_group UUID;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('sample-claim:' || NEW.project_id::text, 0));
    SELECT project_id, publication_group_id INTO source_project, source_group FROM publication_round WHERE id=NEW.round_id;
    IF source_project IS DISTINCT FROM NEW.project_id THEN
        RAISE EXCEPTION 'claim round belongs to a different project' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM sample_work_claim c JOIN publication_round r ON r.id=c.round_id
        WHERE c.project_id=NEW.project_id AND c.sample_id=NEW.sample_id AND c.round_id<>NEW.round_id
          AND (source_group IS NULL OR r.publication_group_id IS DISTINCT FROM source_group)
    ) THEN
        RAISE EXCEPTION 'sample is already claimed by another publication' USING ERRCODE='23505';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER validate_parallel_sample_claim_before_insert BEFORE INSERT ON sample_work_claim
    FOR EACH ROW EXECUTE FUNCTION validate_parallel_sample_claim();
CREATE INDEX ix_round_publication_group ON publication_round(publication_group_id) WHERE publication_group_id IS NOT NULL;
GRANT SELECT, INSERT ON publication_group TO tss_labeling_app;
GRANT SELECT, INSERT ON publication_group_execution TO tss_labeling_app;
GRANT UPDATE(publication_group_id) ON publication_round TO tss_labeling_app;
