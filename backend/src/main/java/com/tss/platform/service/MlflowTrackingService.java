package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingMlflowProperties;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.entity.TrainingExperimentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MlflowTrackingService {

    private static final Logger LOG = LoggerFactory.getLogger(MlflowTrackingService.class);

    private final TrainingMlflowProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public MlflowTrackingService(TrainingMlflowProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String createRun(
            TrainingExperimentVersion task,
            ModelVersion modelVersion,
            DatasetVersion datasetVersion
    ) {
        if (!properties.isEnabled()) {
            return null;
        }
        String experimentId = ensureExperiment();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("experiment_id", experimentId);
        body.put("start_time", Instant.now().toEpochMilli());
        body.put("tags", List.of(
                tag("mlflow.runName", task.getName() == null ? task.getId() : task.getName()),
                tag("tss.training_id", task.getId()),
                tag("tss.experiment_id", task.getExperimentId()),
                tag("tss.model_version_id", task.getModelVersionId()),
                tag("tss.dataset_version_id", task.getDatasetVersionId()),
                tag("tss.model_storage_path", modelVersion.getStoragePath()),
                tag("tss.dataset_storage_path", datasetVersion.getStoragePath())
        ));
        Map<?, ?> response = post("/api/2.0/mlflow/runs/create", body);
        Object run = response.get("run");
        if (run instanceof Map<?, ?> runMap) {
            Object info = runMap.get("info");
            if (info instanceof Map<?, ?> infoMap && infoMap.get("run_id") != null) {
                return infoMap.get("run_id").toString();
            }
        }
        throw new IllegalStateException("MLflow 创建 run 未返回 run_id");
    }

    public void logMetrics(String runId, Map<String, Double> metrics, int step) {
        if (!canWrite(runId) || metrics.isEmpty()) {
            return;
        }
        long timestamp = Instant.now().toEpochMilli();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map.Entry<String, Double> metric : metrics.entrySet()) {
            records.add(Map.of(
                    "key", metric.getKey(),
                    "value", metric.getValue(),
                    "timestamp", timestamp,
                    "step", step
            ));
        }
        post("/api/2.0/mlflow/runs/log-batch", Map.of(
                "run_id", runId,
                "metrics", records
        ));
    }

    /**
     * 把训练指标写入指定的 MLflow 实例（K8s 训练 worker 回调携带的 tracking-uri + runId）。
     * 与 {@link #logMetrics} 不同：这里不依赖后端配置的 tracking-uri，而是直接用回调带来的地址，
     * 用于把训练脚本产出的最终指标补写到 MLflow，供前端指标可视化读取。
     * 最佳努力写入：失败仅记录日志，不影响训练结果回调。
     */
    public void logMetricsToUri(String trackingUri, String runId, Map<String, Double> metrics, int step) {
        if (runId == null || runId.isBlank() || metrics == null || metrics.isEmpty()) {
            return;
        }
        String base = trackingUri;
        if (base == null || base.isBlank()) {
            base = baseUrl(); // 回调没带地址时，用后端配置的 tracking-uri
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        long timestamp = Instant.now().toEpochMilli();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map.Entry<String, Double> metric : metrics.entrySet()) {
            records.add(Map.of(
                    "key", metric.getKey(),
                    "value", metric.getValue(),
                    "timestamp", timestamp,
                    "step", step
            ));
        }
        try {
            postToBase(base, "/api/2.0/mlflow/runs/log-batch", Map.of(
                    "run_id", runId,
                    "metrics", records
            ));
        } catch (Exception primary) {
            // worker 回调带的 tracking-uri 通常是集群内服务名（如 http://tss-mlflow:5000）。
            // 后端容器跑在 host 网络、不在集群内，解析不了该域名会失败；但该地址与后端配置的
            // tracking-uri 指向同一份 mlflow-lite 数据，这里回退到后端自己的地址重写一次。
            try {
                String fallback = baseUrl();
                if (fallback != null && !fallback.isBlank() && !fallback.equalsIgnoreCase(base)) {
                    String fb = fallback.endsWith("/") ? fallback.substring(0, fallback.length() - 1) : fallback;
                    postToBase(fb, "/api/2.0/mlflow/runs/log-batch", Map.of(
                            "run_id", runId,
                            "metrics", records
                    ));
                    LOG.info("训练指标已通过后端 tracking-uri 补写到 MLflow: runId={}", runId);
                    return;
                }
            } catch (Exception ignored) {
                // 回退也失败时按原始异常记录
            }
            LOG.warn("训练指标同步到 MLflow 失败: runId={}, error={}", runId, primary.getMessage());
        }
    }

    public void logParams(String runId, Map<String, String> params) {
        if (!canWrite(runId) || params.isEmpty()) {
            return;
        }
        List<Map<String, String>> records = new ArrayList<>();
        for (Map.Entry<String, String> param : params.entrySet()) {
            records.add(Map.of("key", param.getKey(), "value", param.getValue()));
        }
        post("/api/2.0/mlflow/runs/log-batch", Map.of(
                "run_id", runId,
                "params", records
        ));
    }

    public void finishRun(String runId, boolean success) {
        if (!canWrite(runId)) {
            return;
        }
        post("/api/2.0/mlflow/runs/update", Map.of(
                "run_id", runId,
                "status", success ? "FINISHED" : "FAILED",
                "end_time", Instant.now().toEpochMilli()
        ));
    }

    private String ensureExperiment() {
        try {
            Map<?, ?> response = restTemplate.getForObject(
                    baseUrl() + "/api/2.0/mlflow/experiments/get-by-name?experiment_name={name}",
                    Map.class,
                    properties.getExperimentName()
            );
            String existing = experimentIdFrom(response);
            if (existing != null) {
                return existing;
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
        }

        try {
            Map<?, ?> created = post("/api/2.0/mlflow/experiments/create", Map.of(
                    "name", properties.getExperimentName()
            ));
            Object experimentId = created.get("experiment_id");
            if (experimentId != null) {
                return experimentId.toString();
            }
        } catch (RestClientException ignored) {
            Map<?, ?> response = restTemplate.getForObject(
                    baseUrl() + "/api/2.0/mlflow/experiments/get-by-name?experiment_name={name}",
                    Map.class,
                    properties.getExperimentName()
            );
            String existing = experimentIdFrom(response);
            if (existing != null) {
                return existing;
            }
            throw ignored;
        }
        throw new IllegalStateException("MLflow 创建 experiment 未返回 experiment_id");
    }

    private String experimentIdFrom(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object experiment = response.get("experiment");
        if (experiment instanceof Map<?, ?> map && map.get("experiment_id") != null) {
            return map.get("experiment_id").toString();
        }
        return null;
    }

    private Map<String, String> tag(String key, String value) {
        return Map.of("key", key, "value", value == null ? "" : value);
    }

    private Map<?, ?> post(String path, Object body) {
        return postToBase(baseUrl(), path, body);
    }

    private Map<?, ?> postToBase(String base, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String raw = objectMapper.writeValueAsString(body);
            return restTemplate.postForObject(base + path, new HttpEntity<>(raw, headers), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("调用 MLflow 失败: " + e.getMessage(), e);
        }
    }

    private boolean canWrite(String runId) {
        return properties.isEnabled() && runId != null && !runId.isBlank();
    }

    private String baseUrl() {
        String raw = properties.getTrackingUri();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("training.mlflow.tracking-uri 不能为空");
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }
}
