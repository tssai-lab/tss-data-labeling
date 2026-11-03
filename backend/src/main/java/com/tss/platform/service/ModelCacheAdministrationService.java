package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.modelcache.ModelCacheDtos;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.modelcache.ModelCacheVolumeNaming;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ModelCacheAdministrationService {

    private static final String RESULT_PREFIX = "MODEL_CACHE_RESULT_JSON=";
    private static final String CACHE_READY_LABEL = "tss.ai/model-cache-ready";
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern K8S_NODE_PATTERN =
            Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,251}[a-z0-9])?");

    private final InferenceModelCacheProperties cacheProperties;
    private final TrainingKubernetesProperties kubernetesProperties;
    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner commandRunner;
    private final ComputeServerRepository serverRepository;
    private final ObjectMapper objectMapper;
    private final AuthContext authContext;
    private final AuditRecordService auditRecordService;

    @Value("${inference.kubernetes.worker-image:tss-inference-worker:local}")
    private String workerImage;

    @Value("${inference.kubernetes.worker-image-pull-policy:IfNotPresent}")
    private String workerImagePullPolicy;

    public ModelCacheAdministrationService(
            InferenceModelCacheProperties cacheProperties,
            TrainingKubernetesProperties kubernetesProperties,
            TrainingEnvironmentService environmentService,
            ShellCommandRunner commandRunner,
            ComputeServerRepository serverRepository,
            ObjectMapper objectMapper,
            AuthContext authContext,
            AuditRecordService auditRecordService
    ) {
        this.cacheProperties = cacheProperties;
        this.kubernetesProperties = kubernetesProperties;
        this.environmentService = environmentService;
        this.commandRunner = commandRunner;
        this.serverRepository = serverRepository;
        this.objectMapper = objectMapper;
        this.authContext = authContext;
        this.auditRecordService = auditRecordService;
    }

    public ModelCacheDtos.Overview overview() {
        return overview(new ModelCachePolicy(
                cacheProperties.getMaxBytes(),
                cacheProperties.getMinFreeBytes(),
                cacheProperties.getRuntimeReserveBytes(),
                null
        ));
    }

    public ModelCacheDtos.Overview overview(ModelCachePolicy policy) {
        requireAdministrator();
        List<ComputeServer> servers = serverRepository.findByDeletedFalse().stream()
                .sorted(Comparator.comparing(ComputeServer::getServerIp))
                .toList();
        List<ModelCacheDtos.Node> nodes = new ArrayList<>();
        for (ComputeServer server : servers) {
            boolean ready = cacheReady(server);
            if (!cacheProperties.isEnabled()) {
                nodes.add(emptyNode(server, ready, "model cache is disabled"));
            } else if (!Boolean.TRUE.equals(server.getEnabled())) {
                nodes.add(emptyNode(server, ready, "compute node is disabled"));
            } else if (!ready) {
                nodes.add(emptyNode(server, false, "compute node is not cache-ready"));
            } else {
                nodes.add(inspectNode(server));
            }
        }
        List<ModelCacheDtos.Node> calculated = nodes.stream()
                .map(node -> withPolicyCapacity(node, policy))
                .toList();
        return new ModelCacheDtos.Overview(
                cacheProperties.isEnabled(),
                policy.maxBytes(),
                policy.minFreeBytes(),
                policy.runtimeReserveBytes(),
                policy.emptyCacheGateBytes(),
                policy.updatedAt(),
                calculated
        );
    }

    List<ModelCacheDtos.Node> inspectPolicyNodes() {
        if (!cacheProperties.isEnabled()) {
            return List.of();
        }
        return serverRepository.findByDeletedFalse().stream()
                .filter(server -> Boolean.TRUE.equals(server.getEnabled()))
                .filter(this::cacheReady)
                .sorted(Comparator.comparing(ComputeServer::getServerIp))
                .map(this::inspectNode)
                .toList();
    }

    public ModelCacheDtos.ClearResponse clear(ModelCacheDtos.ClearRequest request) {
        requireSuperAdministrator();
        if (!cacheProperties.isEnabled()) {
            throw new IllegalStateException("model cache is disabled");
        }
        ValidatedClearRequest validated = validateClearRequest(request);
        List<ModelCacheDtos.ClearNodeResult> results = new ArrayList<>();
        for (ComputeServer server : validated.servers()) {
            if (!cacheReady(server)) {
                results.add(new ModelCacheDtos.ClearNodeResult(
                        server.getServerIp(), nodeName(server), List.of(), List.of(), List.of(),
                        "compute node is not cache-ready"));
                continue;
            }
            results.add(clearNode(server, validated.sha256s(), validated.clearAll()));
        }

        boolean failed = results.stream().anyMatch(result -> result.error() != null);
        String detail = "MODEL_CACHE_CLEAR nodes=" + validated.servers().stream()
                .map(ComputeServer::getServerIp).toList()
                + " clearAll=" + validated.clearAll()
                + " keys=" + validated.sha256s();
        if (failed) {
            auditRecordService.recordFailed(
                    AuditActionType.DELETE, AuditObjectType.MODEL, "model-cache",
                    "one or more cache nodes failed", detail);
        } else {
            auditRecordService.recordSuccess(
                    AuditActionType.DELETE, AuditObjectType.MODEL, "model-cache", detail);
        }
        return new ModelCacheDtos.ClearResponse(List.copyOf(results));
    }

    private ModelCacheDtos.Node inspectNode(ComputeServer server) {
        try {
            JsonNode result = runManager(server, Map.of("action", "inspect"));
            List<ModelCacheDtos.Entry> entries = new ArrayList<>();
            for (JsonNode entry : result.path("entries")) {
                entries.add(new ModelCacheDtos.Entry(
                        entry.path("sha256").asText(),
                        entry.path("storagePath").asText(),
                        entry.path("artifactSizeBytes").asLong(),
                        entry.path("dataSizeBytes").asLong(),
                        entry.path("diskSizeBytes").asLong(),
                        entry.path("createdAtEpochSeconds").asLong(),
                        entry.path("lastUsedAtEpochSeconds").asLong(),
                        entry.path("inUse").asBoolean(),
                        entry.path("valid").asBoolean()
                ));
            }
            return new ModelCacheDtos.Node(
                    server.getServerIp(), server.getHostname(), nodeName(server), true,
                    result.path("usedBytes").asLong(),
                    result.path("diskFreeBytes").asLong(),
                    result.path("diskTotalBytes").asLong(),
                    0,
                    0,
                    List.copyOf(entries), null
            );
        } catch (Exception exception) {
            return emptyNode(server, true, safeError(exception));
        }
    }

    private ModelCacheDtos.ClearNodeResult clearNode(
            ComputeServer server,
            List<String> sha256s,
            boolean clearAll
    ) {
        try {
            JsonNode result = runManager(server, Map.of(
                    "action", "clear",
                    "clearAll", clearAll,
                    "keys", sha256s
            ));
            return new ModelCacheDtos.ClearNodeResult(
                    server.getServerIp(), nodeName(server),
                    stringList(result.path("cleared")),
                    stringList(result.path("inUse")),
                    stringList(result.path("notFound")),
                    null
            );
        } catch (Exception exception) {
            return new ModelCacheDtos.ClearNodeResult(
                    server.getServerIp(), nodeName(server), List.of(), List.of(), List.of(),
                    safeError(exception));
        }
    }

    private JsonNode runManager(ComputeServer server, Map<String, Object> command) throws Exception {
        String nodeName = nodeName(server);
        validateNodeName(nodeName);
        String podName = "tss-model-cache-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String commandJson = objectMapper.writeValueAsString(command);
        String yaml = managerPodYaml(podName, nodeName, commandJson);
        Path kubeconfig = environmentService.resolveKubeconfig();
        Path workingDirectory = environmentService.resolveProjectRoot();
        List<String> apply = environmentService.kubectlCommand(kubeconfig, "apply", "-f", "-");
        try {
            ShellCommandRunner.CommandResult applied =
                    commandRunner.runWithInput(apply, workingDirectory, yaml, 30);
            if (!applied.success()) {
                throw new IllegalStateException("cache manager Pod submission failed: "
                        + applied.errorMessage() + " " + applied.output());
            }

            List<String> wait = environmentService.kubectlCommand(
                    kubeconfig,
                    "wait", "--for=jsonpath={.status.phase}=Succeeded",
                    "pod/" + podName,
                    "-n", kubernetesProperties.getNamespace(),
                    "--timeout=90s"
            );
            ShellCommandRunner.CommandResult waited = commandRunner.run(wait, workingDirectory, 100);
            List<String> logs = environmentService.kubectlCommand(
                    kubeconfig, "logs", podName, "-n", kubernetesProperties.getNamespace()
            );
            ShellCommandRunner.CommandResult logged = commandRunner.run(logs, workingDirectory, 30);
            if (!waited.success() || !logged.success()) {
                throw new IllegalStateException("cache manager Pod failed: "
                        + waited.output() + " " + logged.output());
            }
            return parseManagerResult(logged.output());
        } finally {
            List<String> delete = environmentService.kubectlCommand(
                    kubeconfig,
                    "delete", "pod", podName,
                    "-n", kubernetesProperties.getNamespace(),
                    "--ignore-not-found", "--wait=false"
            );
            commandRunner.run(delete, workingDirectory, 30);
        }
    }

    private JsonNode parseManagerResult(String output) throws Exception {
        if (output != null) {
            for (String line : output.split("\\R")) {
                int prefix = line.indexOf(RESULT_PREFIX);
                if (prefix >= 0) {
                    return objectMapper.readTree(line.substring(prefix + RESULT_PREFIX.length()));
                }
            }
        }
        throw new IllegalStateException("cache manager result is missing");
    }

    private String managerPodYaml(String podName, String nodeName, String commandJson) {
        requireSafeAbsolutePath(cacheProperties.getNodePath(), "model cache node path");
        String mountPath = requireSafeAbsolutePath(cacheProperties.getMountPath(), "model cache mount path");
        String claimName = ModelCacheVolumeNaming.claimNameForNode(nodeName);
        if (workerImage == null || workerImage.isBlank()) {
            throw new IllegalStateException("inference worker image is not configured");
        }
        return """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/name: tss-model-cache-manager
                spec:
                  nodeName: %s
                  serviceAccountName: %s
                  automountServiceAccountToken: false
                  restartPolicy: Never
                  securityContext:
                    runAsNonRoot: true
                    runAsUser: 10001
                    runAsGroup: 10001
                    fsGroup: 10001
                    seccompProfile:
                      type: RuntimeDefault
                  volumes:
                    - name: model-cache
                      persistentVolumeClaim:
                        claimName: %s
                  containers:
                    - name: manager
                      image: %s
                      imagePullPolicy: %s
                      volumeMounts:
                        - name: model-cache
                          mountPath: %s
                      env:
                        - name: INFERENCE_WORKER_MODE
                          value: "manage-model-cache"
                        - name: MODEL_CACHE_ROOT
                          value: %s
                        - name: MODEL_CACHE_COMMAND_JSON
                          value: %s
                      resources:
                        requests:
                          cpu: "100m"
                          memory: "128Mi"
                        limits:
                          cpu: "250m"
                          memory: "256Mi"
                          ephemeral-storage: "128Mi"
                      securityContext:
                        allowPrivilegeEscalation: false
                        capabilities:
                          drop:
                            - ALL
                """.formatted(
                podName,
                quote(kubernetesProperties.getNamespace()),
                quote(nodeName),
                quote(kubernetesProperties.getServiceAccount()),
                quote(claimName),
                quote(workerImage),
                workerImagePullPolicy,
                quote(mountPath),
                quote(mountPath),
                quote(commandJson)
        );
    }

    private ValidatedClearRequest validateClearRequest(ModelCacheDtos.ClearRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("clear request is required");
        }
        List<String> serverIps = distinct(request.getServerIps());
        List<String> sha256s = distinct(request.getSha256s()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        if (serverIps.isEmpty() || serverIps.size() > 100) {
            throw new IllegalArgumentException("choose 1 to 100 compute nodes");
        }
        if (sha256s.size() > 1000 || sha256s.stream().anyMatch(value -> !SHA256_PATTERN.matcher(value).matches())) {
            throw new IllegalArgumentException("cache keys must be at most 1000 SHA-256 digests");
        }
        if (request.isClearAll() == !sha256s.isEmpty()) {
            throw new IllegalArgumentException("choose either clearAll or one or more cache keys");
        }
        List<ComputeServer> servers = serverIps.stream().map(serverIp ->
                serverRepository.findByServerIpAndDeletedFalse(serverIp)
                        .orElseThrow(() -> new IllegalArgumentException("compute node does not exist: " + serverIp))
        ).toList();
        return new ValidatedClearRequest(servers, sha256s, request.isClearAll());
    }

    private List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private boolean cacheReady(ComputeServer server) {
        if (server.getK8sLabelsJson() == null || server.getK8sLabelsJson().isBlank()) {
            return false;
        }
        try {
            return "true".equalsIgnoreCase(
                    objectMapper.readTree(server.getK8sLabelsJson()).path(CACHE_READY_LABEL).asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private ModelCacheDtos.Node emptyNode(ComputeServer server, boolean ready, String error) {
        return new ModelCacheDtos.Node(
                server.getServerIp(), server.getHostname(), nodeName(server), ready,
                0, 0, 0, 0, 0, List.of(), error);
    }

    private ModelCacheDtos.Node withPolicyCapacity(
            ModelCacheDtos.Node node,
            ModelCachePolicy policy
    ) {
        if (node.error() != null || !node.cacheReady()) {
            return node;
        }
        long required;
        try {
            required = policy.requiredAvailableBytes(node.usedBytes());
        } catch (IllegalArgumentException exception) {
            return new ModelCacheDtos.Node(
                    node.serverIp(), node.hostname(), node.k8sNodeName(), node.cacheReady(),
                    node.usedBytes(), node.diskFreeBytes(), node.diskTotalBytes(),
                    0, 0, node.entries(), exception.getMessage()
            );
        }
        return new ModelCacheDtos.Node(
                node.serverIp(), node.hostname(), node.k8sNodeName(), node.cacheReady(),
                node.usedBytes(), node.diskFreeBytes(), node.diskTotalBytes(),
                required, node.diskFreeBytes() - required, node.entries(), node.error()
        );
    }

    private String nodeName(ComputeServer server) {
        return server.getK8sNodeName() == null || server.getK8sNodeName().isBlank()
                ? server.getServerIp() : server.getK8sNodeName().trim();
    }

    private void validateNodeName(String nodeName) {
        if (nodeName == null || !K8S_NODE_PATTERN.matcher(nodeName).matches()) {
            throw new IllegalArgumentException("compute node has an invalid Kubernetes node name");
        }
    }

    private String requireSafeAbsolutePath(String value, String name) {
        String path = value == null ? "" : value.trim();
        if (!path.startsWith("/") || path.equals("/") || path.contains("//")) {
            throw new IllegalStateException(name + " must be a non-root absolute path");
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalStateException(name + " must not contain dot segments");
            }
        }
        return path;
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private void requireAdministrator() {
        if (!authContext.isAdmin()) {
            throw new CodeApprovalForbiddenException();
        }
    }

    private void requireSuperAdministrator() {
        if (!authContext.isSuperAdmin()) {
            throw new CodeApprovalForbiddenException();
        }
    }

    private record ValidatedClearRequest(
            List<ComputeServer> servers,
            List<String> sha256s,
            boolean clearAll
    ) {
    }
}
