ALTER TABLE platform_system_config
    ADD COLUMN pod_quota INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN job_quota INTEGER NOT NULL DEFAULT 20,
    ADD COLUMN job_ttl_seconds_after_finished INTEGER NOT NULL DEFAULT 3600;

ALTER TABLE platform_system_config
    ADD CONSTRAINT ck_platform_system_config_pod_quota
        CHECK (pod_quota BETWEEN 1 AND 50),
    ADD CONSTRAINT ck_platform_system_config_job_quota
        CHECK (job_quota BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_platform_system_config_job_ttl
        CHECK (job_ttl_seconds_after_finished BETWEEN 60 AND 3600);

COMMENT ON COLUMN platform_system_config.pod_quota
    IS 'Hard limit written to both pods and count/pods in the managed ResourceQuota';
COMMENT ON COLUMN platform_system_config.job_quota
    IS 'Hard limit written to count/jobs.batch in the managed ResourceQuota';
COMMENT ON COLUMN platform_system_config.job_ttl_seconds_after_finished
    IS 'TTL used only when rendering newly created training or inference Jobs';
