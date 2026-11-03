-- A missing sample is different from a real zero.  These columns used to force
-- every unavailable metric to 0, so the API could not represent "no data".
ALTER TABLE IF EXISTS server_metric_snapshot
    ALTER COLUMN cpu_rate DROP DEFAULT,
    ALTER COLUMN cpu_rate DROP NOT NULL,
    ALTER COLUMN mem_rate DROP DEFAULT,
    ALTER COLUMN mem_rate DROP NOT NULL,
    ALTER COLUMN gpu_rate DROP DEFAULT,
    ALTER COLUMN gpu_rate DROP NOT NULL,
    ALTER COLUMN gpu_mem_rate DROP DEFAULT,
    ALTER COLUMN gpu_mem_rate DROP NOT NULL,
    ALTER COLUMN disk_rate DROP DEFAULT,
    ALTER COLUMN disk_rate DROP NOT NULL,
    ALTER COLUMN network_in DROP DEFAULT,
    ALTER COLUMN network_in DROP NOT NULL,
    ALTER COLUMN network_out DROP DEFAULT,
    ALTER COLUMN network_out DROP NOT NULL,
    ALTER COLUMN gpu_temp DROP DEFAULT,
    ALTER COLUMN gpu_temp DROP NOT NULL;
