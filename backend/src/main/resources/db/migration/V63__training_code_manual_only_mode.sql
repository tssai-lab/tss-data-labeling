ALTER TABLE platform_system_config
    DROP CONSTRAINT ck_platform_system_config_training_code_review_mode,
    ADD CONSTRAINT ck_platform_system_config_training_code_review_mode
        CHECK (
            training_code_review_mode IN (
                'DIRECT_PASS',
                'STANDARD_REVIEW',
                'MANUAL_ONLY'
            )
        );
