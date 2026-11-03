CREATE TABLE IF NOT EXISTS scheduler_lock (
    name VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    locked_until TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scheduler_lock_locked_until
    ON scheduler_lock (locked_until);
