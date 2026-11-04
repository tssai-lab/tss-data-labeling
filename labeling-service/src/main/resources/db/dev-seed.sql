-- 仅用于本机联调。调用 psql 时必须传入真实统一身份 user ID 和展示名。
SET search_path TO labeling;

INSERT INTO actor (id, kind, identity_user_id, display_name)
VALUES (gen_random_uuid(), 'HUMAN', :'identity_user_id', :'display_name')
ON CONFLICT (identity_user_id) DO UPDATE
SET display_name = EXCLUDED.display_name,
    updated_at = CURRENT_TIMESTAMP
RETURNING id AS demo_actor_id \gset

INSERT INTO project (id, owner_actor_id, name, description, task_type)
VALUES (
    'fa2336cf-6baf-4ff1-8f64-fc1fb886bdf1',
    :'demo_actor_id'::uuid,
    '本机接口联调示例',
    '开发用示例项目；可通过创建接口再创建自己的项目。',
    'IMAGE_CLASSIFICATION'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO spec_version
    (id, project_id, version_no, task_type, labels, instructions, editor_config, schema_version)
VALUES (
    'ed6cc455-1708-409f-b2f9-8dc80e09f839',
    'fa2336cf-6baf-4ff1-8f64-fc1fb886bdf1',
    1,
    'IMAGE_CLASSIFICATION',
    '[{"id":"normal","name":"正常"},{"id":"defect","name":"缺陷"}]'::jsonb,
    '请根据图像内容选择一个标签。',
    '{}'::jsonb,
    'v1'
)
ON CONFLICT (id) DO NOTHING;
