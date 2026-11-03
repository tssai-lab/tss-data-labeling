CREATE UNIQUE INDEX IF NOT EXISTS uk_model_version_asset_version
    ON model_version (asset_id, version);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_version_asset_version
    ON dataset_version (asset_id, version);

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_upload_chunk_part
    ON model_upload_chunk (upload_id, part_index);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_upload_chunk_part
    ON dataset_upload_chunk (upload_id, part_index);

CREATE UNIQUE INDEX IF NOT EXISTS uk_training_experiment_version
    ON training_experiment_version (experiment_id, version_no);

ALTER TABLE model_version
    ADD CONSTRAINT fk_model_version_asset
        FOREIGN KEY (asset_id) REFERENCES model_asset (id);

ALTER TABLE model_upload_chunk
    ADD CONSTRAINT fk_model_upload_chunk_session
        FOREIGN KEY (upload_id) REFERENCES model_upload_session (id)
        ON DELETE CASCADE;

ALTER TABLE model_upload_session
    ADD CONSTRAINT fk_model_upload_session_asset
        FOREIGN KEY (asset_id) REFERENCES model_asset (id)
        ON DELETE SET NULL;

ALTER TABLE model_upload_session
    ADD CONSTRAINT fk_model_upload_session_version
        FOREIGN KEY (version_id) REFERENCES model_version (id)
        ON DELETE SET NULL;

ALTER TABLE dataset_version
    ADD CONSTRAINT fk_dataset_version_asset
        FOREIGN KEY (asset_id) REFERENCES dataset_asset (id);

ALTER TABLE dataset_upload_chunk
    ADD CONSTRAINT fk_dataset_upload_chunk_session
        FOREIGN KEY (upload_id) REFERENCES dataset_upload_session (id)
        ON DELETE CASCADE;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT fk_dataset_upload_session_asset
        FOREIGN KEY (asset_id) REFERENCES dataset_asset (id)
        ON DELETE SET NULL;

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT fk_dataset_upload_session_version
        FOREIGN KEY (version_id) REFERENCES dataset_version (id)
        ON DELETE SET NULL;

ALTER TABLE training_experiment_version
    ADD CONSTRAINT fk_training_experiment_model_version
        FOREIGN KEY (model_version_id) REFERENCES model_version (id);

ALTER TABLE training_experiment_version
    ADD CONSTRAINT fk_training_experiment_dataset_version
        FOREIGN KEY (dataset_version_id) REFERENCES dataset_version (id);

ALTER TABLE model_asset
    ADD CONSTRAINT ck_model_asset_type
        CHECK (type IS NULL OR type IN ('CV', 'NLP'));

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_type
        CHECK (type IS NULL OR type IN ('CV', 'NLP'));

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_cv_task_type
        CHECK (cv_task_type IS NULL OR cv_task_type IN (
            'IMAGE_CLASSIFICATION',
            'OBJECT_DETECTION',
            'SEMANTIC_SEGMENTATION',
            'INSTANCE_SEGMENTATION',
            'UNLABELED',
            'OTHER'
        ));

ALTER TABLE dataset_asset
    ADD CONSTRAINT ck_dataset_asset_annotation_format
        CHECK (annotation_format IS NULL OR annotation_format IN (
            'NONE',
            'FOLDER_CLASSIFICATION',
            'CSV',
            'YOLO',
            'COCO',
            'VOC',
            'MASK',
            'LABELME',
            'OTHER'
        ));

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_cv_task_type
        CHECK (cv_task_type IS NULL OR cv_task_type IN (
            'IMAGE_CLASSIFICATION',
            'OBJECT_DETECTION',
            'SEMANTIC_SEGMENTATION',
            'INSTANCE_SEGMENTATION',
            'UNLABELED',
            'OTHER'
        ));

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_annotation_format
        CHECK (annotation_format IS NULL OR annotation_format IN (
            'NONE',
            'FOLDER_CLASSIFICATION',
            'CSV',
            'YOLO',
            'COCO',
            'VOC',
            'MASK',
            'LABELME',
            'OTHER'
        ));

ALTER TABLE model_upload_session
    ADD CONSTRAINT ck_model_upload_session_status
        CHECK (status IN ('UPLOADING', 'COMPLETING', 'COMPLETED'));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_task_type
        CHECK (task_type IN ('CV', 'NLP'));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_cv_task_type
        CHECK (cv_task_type IS NULL OR cv_task_type IN (
            'IMAGE_CLASSIFICATION',
            'OBJECT_DETECTION',
            'SEMANTIC_SEGMENTATION',
            'INSTANCE_SEGMENTATION',
            'UNLABELED',
            'OTHER'
        ));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_annotation_format
        CHECK (annotation_format IS NULL OR annotation_format IN (
            'NONE',
            'FOLDER_CLASSIFICATION',
            'CSV',
            'YOLO',
            'COCO',
            'VOC',
            'MASK',
            'LABELME',
            'OTHER'
        ));

ALTER TABLE dataset_upload_session
    ADD CONSTRAINT ck_dataset_upload_session_status
        CHECK (status IN ('UPLOADING', 'COMPLETING', 'COMPLETED'));

ALTER TABLE training_experiment_version
    ADD CONSTRAINT ck_training_experiment_status
        CHECK (status IS NULL OR status IN ('pending', 'queued', 'running', 'success', 'failed', 'stopped'));
