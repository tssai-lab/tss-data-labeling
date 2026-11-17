ALTER TABLE annotation_draft DROP CONSTRAINT annotation_draft_result_format_check;
ALTER TABLE annotation_draft ADD CONSTRAINT annotation_draft_result_format_check
    CHECK(result_format IN ('image-classification-v1','label-studio-v1'));
