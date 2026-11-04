ALTER TABLE platform_system_config
    ADD COLUMN model_cache_max_bytes BIGINT NOT NULL DEFAULT 1073741824,
    ADD COLUMN model_cache_min_free_bytes BIGINT NOT NULL DEFAULT 3221225472,
    ADD COLUMN model_cache_runtime_reserve_bytes BIGINT NOT NULL DEFAULT 10737418240;

ALTER TABLE platform_system_config
    ADD CONSTRAINT ck_platform_system_config_model_cache_max
        CHECK (model_cache_max_bytes BETWEEN 1073741824 AND 1099511627776
            AND MOD(model_cache_max_bytes, 1073741824) = 0),
    ADD CONSTRAINT ck_platform_system_config_model_cache_min_free
        CHECK (model_cache_min_free_bytes BETWEEN 1073741824 AND 1099511627776
            AND MOD(model_cache_min_free_bytes, 1073741824) = 0),
    ADD CONSTRAINT ck_platform_system_config_model_cache_runtime_reserve
        CHECK (model_cache_runtime_reserve_bytes BETWEEN 1073741824 AND 1099511627776
            AND MOD(model_cache_runtime_reserve_bytes, 1073741824) = 0);

COMMENT ON COLUMN platform_system_config.model_cache_max_bytes
    IS 'Maximum cooperative model-cache occupancy used by newly created training/inference Jobs';
COMMENT ON COLUMN platform_system_config.model_cache_min_free_bytes
    IS 'Minimum filesystem free space retained while populating the model cache';
COMMENT ON COLUMN platform_system_config.model_cache_runtime_reserve_bytes
    IS 'Additional disk reserved by node/deployment validation for runtime image replacement peaks';
