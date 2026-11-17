-- Native LS data and result content remain versioned plugin payloads in JSONB.
ALTER TABLE sample DROP CONSTRAINT sample_media_type_check;
ALTER TABLE sample ADD CONSTRAINT sample_media_type_check
    CHECK (media_type IN ('image/jpeg','image/png','text/csv','application/json'));

ALTER TABLE project DROP CONSTRAINT ck_project_status;
ALTER TABLE project ADD CONSTRAINT ck_project_status
    CHECK (status IN ('DRAFT','OPEN','PAUSED','ARCHIVED','COMPLETED'));
ALTER TABLE project ADD COLUMN completed_snapshot_id UUID REFERENCES final_snapshot(id);
