ALTER TABLE inference_task
    ADD COLUMN IF NOT EXISTS hardware_target_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS gpu_node_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS gpu_host_index VARCHAR(16),
    ADD COLUMN IF NOT EXISTS gpu_uuid VARCHAR(128),
    ADD COLUMN IF NOT EXISTS gpu_model VARCHAR(255),
    ADD COLUMN IF NOT EXISTS gpu_total_memory_mib BIGINT,
    ADD COLUMN IF NOT EXISTS gpu_memory_limit_mib BIGINT;

ALTER TABLE inference_task
    DROP CONSTRAINT IF EXISTS chk_inference_gpu_selection;

ALTER TABLE inference_task
    ADD CONSTRAINT chk_inference_gpu_selection CHECK (
        (gpu_uuid IS NULL AND gpu_node_name IS NULL AND gpu_host_index IS NULL
            AND gpu_model IS NULL AND gpu_total_memory_mib IS NULL
            AND gpu_memory_limit_mib IS NULL)
        OR
        (hardware_target_id IS NOT NULL AND gpu_uuid IS NOT NULL
            AND gpu_node_name IS NOT NULL AND gpu_host_index IS NOT NULL
            AND gpu_model IS NOT NULL AND gpu_total_memory_mib > 0
            AND (gpu_memory_limit_mib IS NULL
                OR (gpu_memory_limit_mib > 0 AND gpu_memory_limit_mib <= gpu_total_memory_mib)))
    );

CREATE INDEX IF NOT EXISTS idx_inference_task_gpu_uuid
    ON inference_task(gpu_uuid)
    WHERE gpu_uuid IS NOT NULL;
