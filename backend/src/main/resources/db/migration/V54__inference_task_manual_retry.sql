ALTER TABLE inference_task
    ADD COLUMN IF NOT EXISTS current_attempt INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_retries INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE inference_task
    DROP CONSTRAINT IF EXISTS ck_inference_task_retry;

ALTER TABLE inference_task
    ADD CONSTRAINT ck_inference_task_retry
        CHECK (
            current_attempt >= 1
            AND retry_count >= 0
            AND max_retries >= 0
            AND retry_count <= max_retries
            AND current_attempt = retry_count + 1
        );
