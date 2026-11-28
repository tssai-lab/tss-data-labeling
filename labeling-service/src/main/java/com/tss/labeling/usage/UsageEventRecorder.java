package com.tss.labeling.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 记录可追溯的执行用量事实，不计算价格或余额。 */
@Component
public class UsageEventRecorder {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public UsageEventRecorder(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void record(
            String eventKey,
            UUID projectId,
            UUID actorId,
            String metricCode,
            long quantity,
            String unitCode,
            String sourceType,
            UUID sourceId,
            String providerId,
            String providerVersion,
            String annotatorType,
            Map<String, ?> dimensions) {
        if (quantity <= 0) return;
        try {
            db.update(
                    """
                    INSERT INTO usage_event(event_id,event_key,project_id,actor_id,metric_code,quantity,unit_code,
                      source_type,source_id,provider_id,provider_version,annotator_type,dimensions)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (event_key) DO NOTHING
                    """,
                    UUID.randomUUID(), eventKey, projectId, actorId, metricCode, quantity, unitCode,
                    sourceType, sourceId, providerId, providerVersion, annotatorType,
                    json.writeValueAsString(dimensions));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("用量事件维度无法序列化", exception);
        }
    }
}
