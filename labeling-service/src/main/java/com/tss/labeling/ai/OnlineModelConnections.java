package com.tss.labeling.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.tss.labeling.common.WorkflowStore.error;

@Service
public class OnlineModelConnections {
    private final WorkflowStore store;

    @Value("${LABELING_ONLINE_IMAGE_AGENT_ID:}")
    private String imageWorker;

    @Value("${LABELING_ONLINE_TIMESERIES_AGENT_ID:}")
    private String seriesWorker;

    public OnlineModelConnections(WorkflowStore store) {
        this.store = store;
    }

    public record Input(String displayName, String protocol, String endpoint, String model,
                        String apiKeyEnv, Boolean enableThinking) {}

    public record Selection(UUID workerId, JsonNode configuration) {}

    @Transactional
    public JsonNode create(ActorContext actor, Input input, String key) {
        admin(actor);
        var operation = "POST:/admin/model-connections";
        JsonNode previous = store.replay(actor, operation, key, input);
        if (previous != null) return previous;
        if (input == null || input.displayName() == null || input.displayName().isBlank()
                || input.displayName().length() > 200 || input.model() == null || input.model().isBlank()
                || input.model().length() > 120 || input.protocol() == null
                || !List.of("openai-chat", "prediction-v1").contains(input.protocol())
                || (input.apiKeyEnv() != null && !input.apiKeyEnv().matches("DASHSCOPE_API_KEY|LABELING_MODEL_API_KEY_[A-Z0-9_]+")))
            throw error(422, "INVALID_MODEL_CONNECTION", "模型连接配置无效");
        URI endpoint;
        try {
            endpoint = URI.create(input.endpoint());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw error(422, "INVALID_MODEL_ENDPOINT", "模型接口需要有效的 HTTPS 地址");
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw error(422, "INVALID_MODEL_ENDPOINT", "模型接口需要有效的 HTTPS 地址");
        UUID id = UUID.randomUUID();
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) store.json.valueToTree(input);
        config.put("connectionId", id.toString());
        store.db.update("INSERT INTO online_model_connection(id,configuration) VALUES (?,?::jsonb)", id, config.toString());
        return store.remember(actor, operation, key, input, id, summary(config));
    }

    @Transactional
    public JsonNode list(ActorContext actor) {
        store.enabled(actor);
        var result = store.json.createArrayNode();
        store.db.query("SELECT configuration::text FROM online_model_connection WHERE enabled ORDER BY created_at,id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.add(summary(store.read(rs.getString(1)))));
        return result;
    }

    @Transactional
    public void disable(ActorContext actor, UUID id) {
        admin(actor);
        if (store.db.update("UPDATE online_model_connection SET enabled=FALSE WHERE id=?", id) == 0)
            throw error(404, "RESOURCE_NOT_FOUND", "模型连接不存在");
    }

    public Selection select(UUID id, String taskType) {
        String worker = switch (taskType) {
            case "IMAGE_CLASSIFICATION" -> imageWorker;
            case "TIMESERIES_ANNOTATION" -> seriesWorker;
            default -> "";
        };
        if (worker.isBlank())
            throw error(422, "ONLINE_WORKER_UNAVAILABLE", "当前任务类型未配置在线预标注 Worker");
        var rows = store.db.queryForList(
                "SELECT configuration::text AS configuration FROM online_model_connection WHERE id=? AND enabled FOR SHARE", id);
        if (rows.isEmpty()) throw error(422, "MODEL_CONNECTION_UNAVAILABLE", "模型连接不存在或已停用");
        return new Selection(UUID.fromString(worker), store.read((String) rows.get(0).get("configuration")));
    }

    private void admin(ActorContext actor) {
        store.enabled(actor);
        if (!actor.labelingAdmin()) throw error(403, "FORBIDDEN", "仅标注管理员可管理模型连接");
    }

    private JsonNode summary(JsonNode config) {
        return store.json.createObjectNode().put("connectionId", config.path("connectionId").asText())
                .put("displayName", config.path("displayName").asText())
                .put("modelVersion", config.path("model").asText());
    }
}
