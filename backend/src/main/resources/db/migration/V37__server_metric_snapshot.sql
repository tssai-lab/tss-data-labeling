CREATE TABLE IF NOT EXISTS server_metric_snapshot (
    server_ip      VARCHAR(45) PRIMARY KEY,
    cpu_rate       DOUBLE PRECISION NOT NULL DEFAULT 0,
    mem_rate       DOUBLE PRECISION NOT NULL DEFAULT 0,
    gpu_rate       DOUBLE PRECISION NOT NULL DEFAULT 0,
    gpu_mem_rate   DOUBLE PRECISION NOT NULL DEFAULT 0,
    disk_rate      DOUBLE PRECISION NOT NULL DEFAULT 0,
    network_in     DOUBLE PRECISION NOT NULL DEFAULT 0,
    network_out    DOUBLE PRECISION NOT NULL DEFAULT 0,
    gpu_temp       DOUBLE PRECISION NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'online',
    last_heartbeat TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);
