-- Existing tasks keep NULL and therefore retain the former global CPU resource behaviour.
-- New tasks store the server-approved profile ID selected at creation time.
ALTER TABLE inference_task
    ADD COLUMN IF NOT EXISTS resource_profile_id VARCHAR(64);
