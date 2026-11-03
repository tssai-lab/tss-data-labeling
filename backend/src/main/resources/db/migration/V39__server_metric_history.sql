CREATE TABLE IF NOT EXISTS server_metric_history (
    id            BIGSERIAL PRIMARY KEY,
    server_ip     VARCHAR(45) NOT NULL,
    cpu_rate      DOUBLE PRECISION,
    mem_rate      DOUBLE PRECISION,
    gpu_rate      DOUBLE PRECISION,
    gpu_mem_rate  DOUBLE PRECISION,
    disk_rate     DOUBLE PRECISION,
    network_in    DOUBLE PRECISION,
    network_out   DOUBLE PRECISION,
    gpu_temp      DOUBLE PRECISION,
    collected_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_history_ip_time ON server_metric_history(server_ip, collected_at DESC);
