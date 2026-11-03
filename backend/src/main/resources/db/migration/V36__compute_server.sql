CREATE TABLE IF NOT EXISTS compute_server (
    server_ip     VARCHAR(45)  PRIMARY KEY,
    hostname      VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'online',
    spec_cpu      VARCHAR(64),
    spec_memory   VARCHAR(64),
    spec_gpu      VARCHAR(128),
    spec_os       VARCHAR(64)  DEFAULT 'Ubuntu 22.04',
    k8s_node_name VARCHAR(256),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compute_server_deleted ON compute_server(deleted);
