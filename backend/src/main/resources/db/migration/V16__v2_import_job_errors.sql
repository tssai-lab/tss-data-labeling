ALTER TABLE import_job
    ADD COLUMN error_code VARCHAR(64);

ALTER TABLE import_job
    ADD COLUMN error_details_json TEXT;
