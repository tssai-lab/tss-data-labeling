ALTER TABLE training_experiment_version
    ADD COLUMN IF NOT EXISTS server_ip VARCHAR(45),
    ADD COLUMN IF NOT EXISTS queue_sort_index INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS priority VARCHAR(8) NOT NULL DEFAULT '中';

ALTER TABLE inference_task
    ADD COLUMN IF NOT EXISTS server_ip VARCHAR(45),
    ADD COLUMN IF NOT EXISTS queue_sort_index INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS priority VARCHAR(8) NOT NULL DEFAULT '中';

CREATE INDEX IF NOT EXISTS idx_training_server_ip ON training_experiment_version(server_ip);
CREATE INDEX IF NOT EXISTS idx_training_queue_sort ON training_experiment_version(server_ip, status, queue_sort_index, priority);
CREATE INDEX IF NOT EXISTS idx_inference_server_ip ON inference_task(server_ip);
CREATE INDEX IF NOT EXISTS idx_inference_queue_sort ON inference_task(server_ip, status, queue_sort_index, priority);
