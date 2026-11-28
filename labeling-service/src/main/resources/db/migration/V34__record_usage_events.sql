-- 仅记录可审计的用量事实，暂不存价格、余额、扣费或账单。
CREATE TABLE usage_event (
    event_id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    project_id UUID NOT NULL REFERENCES project(id),
    actor_id UUID REFERENCES actor(id),
    metric_code VARCHAR(120) NOT NULL,
    quantity NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
    unit_code VARCHAR(32) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id UUID NOT NULL,
    provider_id VARCHAR(120),
    provider_version VARCHAR(64),
    annotator_type VARCHAR(64),
    dimensions JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(dimensions) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_usage_event_project_time ON usage_event(project_id, occurred_at, event_id);
CREATE INDEX ix_usage_event_source ON usage_event(source_type, source_id);

-- 运行账号只能读取和追加事实事件，不能改写历史用量。
GRANT SELECT, INSERT ON usage_event TO tss_labeling_app;
