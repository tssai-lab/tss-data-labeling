-- 项目和导入批次固定可信插件版本，升级规范不改变在途导入的处理器。
ALTER TABLE project ADD COLUMN template_id TEXT;
ALTER TABLE project ADD COLUMN template_version TEXT;
UPDATE project SET template_id=CASE task_type
 WHEN 'IMAGE_CLASSIFICATION' THEN 'image-classification'
 WHEN 'TIMESERIES_ANNOTATION' THEN 'energy-station-day'
 WHEN 'COMPUTER_VISION' THEN 'computer-vision'
 WHEN 'TIME_SERIES' THEN 'time-series' END,
 template_version='1.0.0';
ALTER TABLE import_batch ADD COLUMN template_id TEXT;
ALTER TABLE import_batch ADD COLUMN template_version TEXT;
UPDATE import_batch b SET template_id=p.template_id,template_version=p.template_version FROM project p WHERE p.id=b.project_id;

-- 结果格式由冻结插件契约校验，不再把可安装格式写死为三种。
ALTER TABLE annotation_draft DROP CONSTRAINT annotation_draft_result_format_check;
ALTER TABLE annotation_draft ADD CONSTRAINT annotation_draft_result_format_check
 CHECK (length(btrim(result_format)) BETWEEN 1 AND 120);
