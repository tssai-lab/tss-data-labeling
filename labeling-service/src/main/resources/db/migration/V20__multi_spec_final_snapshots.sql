-- A composite outcome has no single spec on its header. Every item keeps its exact spec.
ALTER TABLE final_snapshot ALTER COLUMN spec_version_id DROP NOT NULL;
ALTER TABLE final_snapshot_item ADD CONSTRAINT fk_snapshot_item_spec
FOREIGN KEY(spec_version_id,project_id) REFERENCES spec_version(id,project_id);
