-- actor 表只保存标注业务身份，不复制统一登录密码或训练平台角色。
CREATE TABLE actor (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL,
    identity_user_id TEXT,
    display_name VARCHAR(200) NOT NULL,
    status TEXT NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_actor_identity_user UNIQUE (identity_user_id),
    CONSTRAINT ck_actor_kind CHECK (kind IN ('HUMAN', 'MODEL')),
    CONSTRAINT ck_actor_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_actor_identity CHECK (
        (kind = 'HUMAN' AND identity_user_id IS NOT NULL
            AND length(btrim(identity_user_id)) > 0)
        OR (kind = 'MODEL' AND identity_user_id IS NULL)
    ),
    CONSTRAINT ck_actor_display_name CHECK (length(btrim(display_name)) > 0)
);

-- 业务账号可以维护参与者，但不能删表、改表或修改迁移记录。
GRANT USAGE ON SCHEMA labeling TO tss_labeling_app;
GRANT SELECT, INSERT, UPDATE ON actor TO tss_labeling_app;
