package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class ModelCachePolicyKubernetesClient {

    public static final String CONFIG_MAP_NAME = "tss-model-cache-policy";
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 500;

    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner shellRunner;
    private final ObjectMapper objectMapper;
    private final TrainingKubernetesProperties properties;

    public ModelCachePolicyKubernetesClient(
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

    public PolicySnapshot read() {
        ShellCommandRunner.CommandResult result = kubectl(
                "get", "configmap", CONFIG_MAP_NAME,
                "-n", properties.getNamespace(), "-o", "json"
        );
        if (!result.success()) {
            if (isNotFound(result)) {
                return PolicySnapshot.absent();
            }
            throw new IllegalStateException("无法读取模型缓存策略 ConfigMap：" + safeError(result));
        }
        return parse(result.output());
    }

    public PolicySnapshot writeAndReadBack(ModelCachePolicy policy) {
        String yaml = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/name: tss-model-cache-policy
                    app.kubernetes.io/part-of: tss-platform
                data:
                  maxBytes: "%d"
                  minFreeBytes: "%d"
                  runtimeReserveBytes: "%d"
                """.formatted(
                CONFIG_MAP_NAME,
                properties.getNamespace(),
                policy.maxBytes(),
                policy.minFreeBytes(),
                policy.runtimeReserveBytes()
        );
        Path kubeconfig = environmentService.resolveKubeconfig();
        ShellCommandRunner.CommandResult result = shellRunner.runWithInput(
                environmentService.kubectlCommand(kubeconfig, "apply", "-f", "-"),
                environmentService.resolveProjectRoot(), yaml, COMMAND_TIMEOUT_SECONDS
        );
        if (!result.success()) {
            throw new IllegalStateException("无法更新模型缓存策略 ConfigMap：" + safeError(result));
        }
        PolicySnapshot actual = read();
        if (!actual.present() || !actual.matches(policy)) {
            throw new IllegalStateException("模型缓存策略 ConfigMap 写入后读回值不一致");
        }
        return actual;
    }

    public void restore(PolicySnapshot snapshot) {
        if (snapshot == null || !snapshot.present()) {
            ShellCommandRunner.CommandResult result = kubectl(
                    "delete", "configmap", CONFIG_MAP_NAME,
                    "-n", properties.getNamespace(), "--ignore-not-found=true"
            );
            if (!result.success()) {
                throw new IllegalStateException("无法删除回滚模型缓存策略 ConfigMap：" + safeError(result));
            }
            return;
        }
        writeAndReadBack(snapshot.toPolicy());
    }

    PolicySnapshot parse(String json) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            return new PolicySnapshot(
                    true,
                    requiredPolicyLong(data, "maxBytes"),
                    requiredPolicyLong(data, "minFreeBytes"),
                    requiredPolicyLong(data, "runtimeReserveBytes")
            );
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("模型缓存策略 ConfigMap 响应无法解析", exception);
        }
    }

    private ShellCommandRunner.CommandResult kubectl(String... args) {
        Path kubeconfig = environmentService.resolveKubeconfig();
        return shellRunner.run(
                environmentService.kubectlCommand(kubeconfig, args),
                environmentService.resolveProjectRoot(),
                COMMAND_TIMEOUT_SECONDS
        );
    }

    private static long requiredPolicyLong(JsonNode data, String key) {
        String raw = data.path(key).asText();
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < PlatformSystemConfig.MIN_MODEL_CACHE_POLICY_BYTES
                    || parsed > PlatformSystemConfig.MAX_MODEL_CACHE_POLICY_BYTES
                    || parsed % PlatformSystemConfig.GIB != 0) {
                throw new NumberFormatException("outside policy range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("模型缓存策略 ConfigMap 字段非法：" + key);
        }
    }

    private static boolean isNotFound(ShellCommandRunner.CommandResult result) {
        String detail = (result.output() == null ? "" : result.output()) + " "
                + (result.errorMessage() == null ? "" : result.errorMessage());
        return detail.toLowerCase().contains("notfound")
                || detail.toLowerCase().contains("not found");
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

    public record PolicySnapshot(
            boolean present,
            long maxBytes,
            long minFreeBytes,
            long runtimeReserveBytes
    ) {
        static PolicySnapshot absent() {
            return new PolicySnapshot(false, 0, 0, 0);
        }

        boolean matches(ModelCachePolicy policy) {
            return maxBytes == policy.maxBytes()
                    && minFreeBytes == policy.minFreeBytes()
                    && runtimeReserveBytes == policy.runtimeReserveBytes();
        }

        ModelCachePolicy toPolicy() {
            return new ModelCachePolicy(maxBytes, minFreeBytes, runtimeReserveBytes, null);
        }
    }
}
