package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto.KubernetesNodeHealth;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto.KubernetesPodIssue;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto.KubernetesWorkloadImage;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KubernetesResourceDiagnosticsService {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesResourceDiagnosticsService.class);
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final long NODE_CACHE_MILLIS = 10_000;

    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner shellRunner;
    private final ObjectMapper objectMapper;
    private final String configuredInferenceImage;
    private CachedNodeHealth cachedNodeHealth;

    public KubernetesResourceDiagnosticsService(
            TrainingEnvironmentService environmentService,
            ShellCommandRunner shellRunner,
            ObjectMapper objectMapper,
            @Value("${inference.kubernetes.worker-image:tss-inference-worker:local}")
            String configuredInferenceImage
    ) {
        this.environmentService = environmentService;
        this.shellRunner = shellRunner;
        this.objectMapper = objectMapper;
        this.configuredInferenceImage = configuredInferenceImage;
    }

    public synchronized NodeHealthCollection collectNodeHealth() {
        Instant now = Instant.now();
        if (cachedNodeHealth != null
                && cachedNodeHealth.collectedAt().isAfter(now.minusMillis(NODE_CACHE_MILLIS))) {
            return cachedNodeHealth.collection();
        }
        ShellCommandRunner.CommandResult result = runKubectl("get", "nodes", "-o", "json");
        if (!result.success()) {
            LOG.warn("Kubernetes 节点诊断采集失败: {}", result.errorMessage());
            return cacheNodeHealth(now,
                    NodeHealthCollection.unavailable("暂时无法读取 Kubernetes 节点状态"));
        }
        try {
            List<KubernetesNodeHealth> nodes = parseNodes(result.output());
            Map<String, KubernetesNodeHealth> byName = new LinkedHashMap<>();
            for (KubernetesNodeHealth node : nodes) {
                byName.put(node.getName(), node);
            }
            return cacheNodeHealth(now, new NodeHealthCollection(true, null, byName));
        } catch (Exception exception) {
            LOG.warn("Kubernetes 节点诊断响应解析失败: {}", exception.getMessage());
            return cacheNodeHealth(now,
                    NodeHealthCollection.unavailable("Kubernetes 节点状态响应无法解析"));
        }
    }

    public KubernetesDiagnosticsDto collectDiagnostics() {
        Instant now = Instant.now();
        KubernetesDiagnosticsDto dto = new KubernetesDiagnosticsDto();
        dto.setCollectedAt(now);
        dto.setConfiguredInferenceImage(configuredInferenceImage);

        NodeHealthCollection nodeCollection = collectNodeHealth();
        dto.setNodes(new ArrayList<>(nodeCollection.nodesByName().values()));

        ShellCommandRunner.CommandResult podsResult = runKubectl("get", "pods", "-A", "-o", "json");
        boolean podsAvailable = false;
        String podsMessage = null;
        if (podsResult.success()) {
            try {
                PodDiagnostics pods = parsePods(podsResult.output());
                dto.setPodIssues(pods.issues());
                dto.setWorkloadImages(pods.images());
                podsAvailable = true;
            } catch (Exception exception) {
                LOG.warn("Kubernetes Pod 诊断响应解析失败: {}", exception.getMessage());
                podsMessage = "Kubernetes Pod 状态响应无法解析";
            }
        } else {
            LOG.warn("Kubernetes Pod 诊断采集失败: {}", podsResult.errorMessage());
            podsMessage = "暂时无法读取 Kubernetes Pod 状态";
        }

        if (nodeCollection.available() && podsAvailable) {
            dto.setCollectionStatus("healthy");
            dto.setMessage("Kubernetes 诊断采集正常");
        } else if (nodeCollection.available() || podsAvailable) {
            dto.setCollectionStatus("degraded");
            dto.setMessage(nodeCollection.available() ? podsMessage : nodeCollection.message());
        } else {
            dto.setCollectionStatus("unavailable");
            dto.setMessage("暂时无法读取 Kubernetes 节点和 Pod 状态");
        }
        return dto;
    }

    List<KubernetesNodeHealth> parseNodes(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<KubernetesNodeHealth> nodes = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            KubernetesNodeHealth node = new KubernetesNodeHealth();
            node.setName(item.path("metadata").path("name").asText());
            node.setUnschedulable(item.path("spec").path("unschedulable").asBoolean(false));
            node.setReady(conditionValue(item, "Ready"));
            node.setMemoryPressure(conditionValue(item, "MemoryPressure"));
            node.setDiskPressure(conditionValue(item, "DiskPressure"));
            node.setPidPressure(conditionValue(item, "PIDPressure"));

            List<String> warnings = new ArrayList<>();
            if (!Boolean.TRUE.equals(node.getReady())) warnings.add("节点未就绪");
            if (Boolean.TRUE.equals(node.getUnschedulable())) warnings.add("节点已禁止调度");
            if (Boolean.TRUE.equals(node.getMemoryPressure())) warnings.add("存在内存压力");
            if (Boolean.TRUE.equals(node.getDiskPressure())) warnings.add("存在磁盘压力");
            if (Boolean.TRUE.equals(node.getPidPressure())) warnings.add("存在进程数压力");

            if (node.getReady() == null) {
                node.setHealthStatus("unavailable");
                node.setMessage("节点缺少 Ready 状态");
            } else if (warnings.isEmpty()) {
                node.setHealthStatus("healthy");
                node.setMessage("节点状态正常");
            } else {
                node.setHealthStatus("warning");
                node.setMessage(String.join("；", warnings));
            }
            nodes.add(node);
        }
        return nodes;
    }

    PodDiagnostics parsePods(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<KubernetesPodIssue> issues = new ArrayList<>();
        List<KubernetesWorkloadImage> images = new ArrayList<>();
        Set<String> issueKeys = new LinkedHashSet<>();
        for (JsonNode pod : root.path("items")) {
            String namespace = pod.path("metadata").path("namespace").asText("default");
            String podName = pod.path("metadata").path("name").asText();
            String nodeName = nullableText(pod.path("spec").path("nodeName"));
            String phase = pod.path("status").path("phase").asText("Unknown");
            int issueCountBefore = issues.size();

            for (JsonNode condition : pod.path("status").path("conditions")) {
                if ("False".equals(condition.path("status").asText())) {
                    String reason = nullableText(condition.path("reason"));
                    if ("PodScheduled".equals(condition.path("type").asText())
                            || "FailedScheduling".equals(reason)) {
                        addIssue(issues, issueKeys, namespace, podName, nodeName, phase,
                                "pod", null, reason == null ? "PodScheduled=False" : reason,
                                nullableText(condition.path("message")), null);
                    }
                }
            }

            readContainerIssues(pod.path("status").path("initContainerStatuses"), "init",
                    namespace, podName, nodeName, phase, issues, issueKeys);
            readContainerIssues(pod.path("status").path("containerStatuses"), "main",
                    namespace, podName, nodeName, phase, issues, issueKeys);

            if (("Pending".equals(phase) || "Failed".equals(phase)) && issues.size() == issueCountBefore) {
                addIssue(issues, issueKeys, namespace, podName, nodeName, phase,
                        "pod", null, phase, "Pod 处于 " + phase + " 状态", null);
            }
            readWorkloadImages(pod, namespace, podName, nodeName, images);
        }
        return new PodDiagnostics(issues, images);
    }

    private void readContainerIssues(
            JsonNode statuses,
            String containerType,
            String namespace,
            String podName,
            String nodeName,
            String phase,
            List<KubernetesPodIssue> issues,
            Set<String> issueKeys
    ) {
        for (JsonNode status : statuses) {
            String containerName = status.path("name").asText();
            JsonNode waiting = status.path("state").path("waiting");
            if (!waiting.isMissingNode() && !waiting.isEmpty()) {
                addIssue(issues, issueKeys, namespace, podName, nodeName, phase,
                        containerType, containerName,
                        defaultText(waiting.path("reason"), "Waiting"),
                        nullableText(waiting.path("message")), null);
            }
            JsonNode terminated = status.path("state").path("terminated");
            if (!terminated.isMissingNode() && !terminated.isEmpty()) {
                int exitCode = terminated.path("exitCode").asInt(0);
                String reason = defaultText(terminated.path("reason"), "Terminated");
                if (exitCode != 0 || "OOMKilled".equals(reason)) {
                    addIssue(issues, issueKeys, namespace, podName, nodeName, phase,
                            containerType, containerName, reason,
                            nullableText(terminated.path("message")), exitCode);
                }
            }
        }
    }

    private void readWorkloadImages(
            JsonNode pod,
            String namespace,
            String podName,
            String nodeName,
            List<KubernetesWorkloadImage> images
    ) {
        String appName = pod.path("metadata").path("labels")
                .path("app.kubernetes.io/name").asText();
        String workloadType;
        if ("tss-inference-job".equals(appName)) {
            workloadType = "inference";
        } else if ("tss-training-job".equals(appName)) {
            workloadType = "training";
        } else {
            return;
        }

        Map<String, String> initImageIds = imageIds(pod.path("status").path("initContainerStatuses"));
        Map<String, String> mainImageIds = imageIds(pod.path("status").path("containerStatuses"));
        addImages(pod.path("spec").path("initContainers"), initImageIds, "init",
                workloadType, namespace, podName, nodeName, images);
        addImages(pod.path("spec").path("containers"), mainImageIds, "main",
                workloadType, namespace, podName, nodeName, images);
    }

    private void addImages(
            JsonNode containers,
            Map<String, String> imageIds,
            String containerType,
            String workloadType,
            String namespace,
            String podName,
            String nodeName,
            List<KubernetesWorkloadImage> images
    ) {
        for (JsonNode container : containers) {
            KubernetesWorkloadImage image = new KubernetesWorkloadImage();
            String containerName = container.path("name").asText();
            String declaredImage = container.path("image").asText();
            image.setNamespace(namespace);
            image.setPodName(podName);
            image.setNodeName(nodeName);
            image.setWorkloadType(workloadType);
            image.setContainerType(containerType);
            image.setContainerName(containerName);
            image.setDeclaredImage(declaredImage);
            image.setImageId(imageIds.get(containerName));
            if ("inference".equals(workloadType) && "inference-worker".equals(containerName)) {
                image.setConfiguredInferenceImageMatch(configuredInferenceImage.equals(declaredImage));
            }
            images.add(image);
        }
    }

    private Map<String, String> imageIds(JsonNode statuses) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode status : statuses) {
            result.put(status.path("name").asText(), nullableText(status.path("imageID")));
        }
        return result;
    }

    private void addIssue(
            List<KubernetesPodIssue> issues,
            Set<String> issueKeys,
            String namespace,
            String podName,
            String nodeName,
            String phase,
            String containerType,
            String containerName,
            String reason,
            String message,
            Integer exitCode
    ) {
        String key = namespace + "/" + podName + "/" + containerType + "/" + containerName + "/" + reason;
        if (!issueKeys.add(key)) {
            return;
        }
        KubernetesPodIssue issue = new KubernetesPodIssue();
        issue.setNamespace(namespace);
        issue.setPodName(podName);
        issue.setNodeName(nodeName);
        issue.setPhase(phase);
        issue.setContainerType(containerType);
        issue.setContainerName(containerName);
        issue.setReason(reason);
        issue.setMessage(sanitizeMessage(message));
        issue.setExitCode(exitCode);
        issues.add(issue);
    }

    private Boolean conditionValue(JsonNode node, String type) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if (type.equals(condition.path("type").asText())) {
                String value = condition.path("status").asText();
                if ("True".equals(value)) return true;
                if ("False".equals(value)) return false;
                return null;
            }
        }
        return null;
    }

    private ShellCommandRunner.CommandResult runKubectl(String... args) {
        return shellRunner.runWithInput(
                environmentService.kubectlCommand(environmentService.resolveKubeconfig(), args),
                environmentService.resolveProjectRoot(),
                "",
                COMMAND_TIMEOUT_SECONDS
        );
    }

    private NodeHealthCollection cacheNodeHealth(Instant collectedAt, NodeHealthCollection collection) {
        cachedNodeHealth = new CachedNodeHealth(collectedAt, collection);
        return collection;
    }

    private String sanitizeMessage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= MAX_MESSAGE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private static String nullableText(JsonNode value) {
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String defaultText(JsonNode value, String fallback) {
        String text = nullableText(value);
        return text == null ? fallback : text;
    }

    public record NodeHealthCollection(
            boolean available,
            String message,
            Map<String, KubernetesNodeHealth> nodesByName
    ) {
        static NodeHealthCollection unavailable(String message) {
            return new NodeHealthCollection(false, message, Map.of());
        }
    }

    record PodDiagnostics(List<KubernetesPodIssue> issues, List<KubernetesWorkloadImage> images) {
    }

    private record CachedNodeHealth(Instant collectedAt, NodeHealthCollection collection) {
    }
}
