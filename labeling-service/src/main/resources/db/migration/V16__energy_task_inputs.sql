-- Registered templates and immutable structured input for station-day energy tasks.
ALTER TABLE spec_version ADD COLUMN template_id TEXT;
ALTER TABLE spec_version ADD COLUMN template_version TEXT;
ALTER TABLE spec_version ADD COLUMN input_schema_id TEXT;
ALTER TABLE spec_version ADD COLUMN result_schema_id TEXT;
ALTER TABLE spec_version ADD COLUMN schema_digest CHAR(64);
UPDATE spec_version SET template_id='image-classification',template_version='1.0.0',input_schema_id='image-classification-input@1.0.0',result_schema_id='image-classification-result@1.0.0' WHERE task_type='IMAGE_CLASSIFICATION';
ALTER TABLE sample DROP CONSTRAINT sample_media_type_check;
ALTER TABLE sample ADD CONSTRAINT sample_media_type_check CHECK(media_type IN ('image/jpeg','image/png','application/json'));
ALTER TABLE annotation_draft DROP CONSTRAINT annotation_draft_result_format_check;
ALTER TABLE annotation_draft ADD CONSTRAINT annotation_draft_result_format_check CHECK(result_format IN ('image-classification-v1','annotation-record-v1'));
CREATE TABLE source_asset (
 id UUID PRIMARY KEY,project_id UUID NOT NULL REFERENCES project(id),batch_id UUID NOT NULL UNIQUE REFERENCES import_batch(id),
 dataset_version TEXT NOT NULL,source_path TEXT NOT NULL,archive_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(id,project_id)
);
CREATE TABLE sample_input (
 sample_id UUID PRIMARY KEY,project_id UUID NOT NULL,asset_id UUID NOT NULL,
 dataset_version TEXT NOT NULL,source_task_id TEXT NOT NULL,station_key TEXT NOT NULL,date_local DATE NOT NULL,
 input_format TEXT NOT NULL,input_hash CHAR(64) NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>=2),
 input_payload JSONB NOT NULL CHECK(jsonb_typeof(input_payload)='object'),
 FOREIGN KEY(sample_id,project_id) REFERENCES sample(id,project_id),FOREIGN KEY(asset_id,project_id) REFERENCES source_asset(id,project_id),
 UNIQUE(project_id,dataset_version,source_task_id),UNIQUE(project_id,dataset_version,station_key,date_local)
);
GRANT SELECT,INSERT ON source_asset,sample_input TO tss_labeling_app;
