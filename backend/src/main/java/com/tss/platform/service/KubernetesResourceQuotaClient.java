package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KubernetesResourceQuotaClient {

    static final String RESOURCE_QUOTA_NAME = "tss-training-quota";
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 500;

    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner shellRunner;
    private final ObjectMapper objectMapper;
    private final TrainingKubernetesProperties properties;

    public KubernetesResourceQuotaClient(
            TrainingEnvironmentService environmentService,
            ShellCommandRunner shellRunner,
            ObjectMapper objectMapper,
            TrainingKubernetesProperties properties
    ) {
        this.environmentService = environmentService;
        this.shellRunner = shellRunner;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public QuotaSnapshot read() {
        ShellCommandRunner.CommandResult result = kubectl(
                "get", "resourcequota", RESOURCE_QUOTA_NAME,
                "-n", properties.getNamespace(),
                "-o", "json"
        );
        if (!result.success()) {
            throw new IllegalStateException("无法读取 Kubernetes ResourceQuota：" + safeError(result));
        }
        return parse(result.output());
    }

    QuotaSnapshot parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode hard = root.path("spec").path("hard");
            JsonNode used = root.path("status").path("used");
            int podQuota = requiredNonNegativeInt(hard, "count/pods", "pods");
            int legacyPodQuota = optionalNonNegativeInt(hard, "pods", podQuota);
            if (legacyPodQuota != podQuota) {
                throw new IllegalStateException("ResourceQuota 的 pods 与 count/pods 不一致");
            }
            int jobQuota = requiredNonNegativeInt(hard, "count/jobs.batch");
            int usedPods = Math.max(
                    optionalNonNegativeInt(used, "count/pods", 0),
                    optionalNonNegativeInt(used, "pods", 0)
            );
            int usedJobs = optionalNonNegativeInt(used, "count/jobs.batch", 0);
            return new QuotaSnapshot(podQuota, jobQuota, usedPods, usedJobs);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Kubernetes ResourceQuota 响应无法解析", exception);
        }
    }

    public QuotaSnapshot writeAndReadBack(int podQuota, int jobQuota) {
        String patch = buildPatch(podQuota, jobQuota);

        ShellCommandRunner.CommandResult result = kubectl(
                "patch", "resourcequota", RESOURCE_QUOTA_NAME,
                "-n", properties.getNamespace(),
                "--type=merge",
                "-p", patch
        );
        if (!result.success()) {
            throw new IllegalStateException("无法更新 Kubernetes ResourceQuota：" + safeError(result));
        }
        QuotaSnapshot actual = read();
        if (actual.podQuota() != podQuota || actual.jobQuota() != jobQuota) {
            throw new IllegalStateException("Kubernetes ResourceQuota 写入后读回值不一致");
        }
        return actual;
    }

    String buildPatch(int podQuota, int jobQuota) {
        String patch;
        try {
            Map<String, Object> hard = new LinkedHashMap<>();
            hard.put("pods", Integer.toString(podQuota));
            hard.put("count/pods", Integer.toString(podQuota));
            hard.put("count/jobs.batch", Integer.toString(jobQuota));
            patch = objectMapper.writeValueAsString(Map.of(
                    "spec", Map.of("hard", hard)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 ResourceQuota 更新内容", exception);
        }
        return patch;
    }

    private ShellCommandRunner.CommandResult kubectl(String... args) {
        Path kubeconfig = environmentService.resolveKubeconfig();
        return shellRunner.run(
                environmentService.kubectlCommand(kubeconfig, args),
                environmentService.resolveProjectRoot(),
                COMMAND_TIMEOUT_SECONDS
        );
    }

    private static int requiredNonNegativeInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                return parseNonNegativeInt(value.asText(), key);
            }
        }
        throw new IllegalStateException("ResourceQuota 缺少字段：" + String.join("/", keys));
    }

    private static int optionalNonNegativeInt(JsonNode node, String key, int fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull()
                ? fallback
                : parseNonNegativeInt(value.asText(), key);
    }

    private static int parseNonNegativeInt(String value, String key) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("ResourceQuota 字段不是非负整数：" + key);
        }
    }

    private static String safeError(ShellCommandRunner.CommandResult result) {
        String message = result.errorMessage();
        if (message == null || message.isBlank()) {
            message = result.output();
        }
        if (message == null || message.isBlank()) {
            return "kubectl 未返回错误详情";
        }
        String normalized = message.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    public record QuotaSnapshot(
            int podQuota,
            int jobQuota,
            int usedPods,
            int usedJobs
    ) {
    }
}
