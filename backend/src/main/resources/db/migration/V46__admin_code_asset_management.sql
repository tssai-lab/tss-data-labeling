ALTER TABLE code_asset_audit_log
    DROP CONSTRAINT ck_code_asset_audit_log_actor;

ALTER TABLE code_asset_audit_log
    ADD CONSTRAINT ck_code_asset_audit_log_actor
        CHECK (
            (
                actor_type IN ('USER', 'ADMIN')
                AND actor_user_id IS NOT NULL
            )
            OR
            (
                actor_type = 'SYSTEM'
                AND actor_user_id IS NULL
            )
        );
