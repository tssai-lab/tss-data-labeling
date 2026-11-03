package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.ComputeServer;

import java.util.Map;

/** Shared node eligibility rules used by both assignment and read-only resource discovery. */
public final class ComputeServerSchedulingPolicy {

    static final String PLATFORM_SCHEDULABLE_LABEL = "tss.ai/platform-schedulable";
    static final String PLATFORM_MAX_ACTIVE_TASKS_LABEL = "tss.ai/platform-max-active-tasks";
    static final String GPU_SCHEDULABLE_LABEL = "tss.ai/gpu-schedulable";
    static final String MODEL_CACHE_READY_LABEL = "tss.ai/model-cache-ready";
    static final String HARDWARE_CLASS_LABEL = "tss.ai/hardware-class";

    private ComputeServerSchedulingPolicy() {
    }

    static boolean matchesNodeSelector(ComputeServer node, Map<String, String> selector) {
        if (selector == null || selector.isEmpty()) {
            return true;
        }
        // 主机名决定精确选卡落到哪台机器，先独立核对，不能被损坏的标签 JSON 绕过。
        String requiredHostname = selector.get("kubernetes.io/hostname");
        if (requiredHostname != null && !requiredHostname.equals(node.getK8sNodeName())) {
            return false;
        }
        if (requiredHostname != null && selector.size() == 1) {
            return true;
        }
        boolean acceleratorRequired = selector.containsKey("tss.ai/accelerator");
        try {
            JsonNode labels = labels(node);
            if (acceleratorRequired
                    && labels != null
                    && "false".equalsIgnoreCase(labels.path(GPU_SCHEDULABLE_LABEL).asText())) {
                return false;
            }
            for (Map.Entry<String, String> entry : selector.entrySet()) {
                if ("kubernetes.io/hostname".equals(entry.getKey())) continue;
                // Kubernetes 主机名就是节点身份；数据库无需再重复保存一个同名标签。
                String actual = labels != null && labels.has(entry.getKey())
                        ? labels.get(entry.getKey()).asText()
                        : null;
                if (!entry.getValue().equals(actual)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return !acceleratorRequired;
        }
    }

    public static boolean isCacheReady(ComputeServer node) {
        try {
            JsonNode labels = labels(node);
            return labels != null && "true".equalsIgnoreCase(
                    labels.path(MODEL_CACHE_READY_LABEL).asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isPlatformSchedulable(ComputeServer node) {
        try {
            JsonNode labels = labels(node);
            if (labels == null) return true;
            JsonNode schedulable = labels.path(PLATFORM_SCHEDULABLE_LABEL);
            return schedulable.isMissingNode()
                    || !"false".equalsIgnoreCase(schedulable.asText());
        } catch (Exception ignored) {
            return true;
        }
    }

    public static boolean isGpuSchedulable(ComputeServer node) {
        try {
            JsonNode labels = labels(node);
            return labels == null || !"false".equalsIgnoreCase(
                    labels.path(GPU_SCHEDULABLE_LABEL).asText());
        } catch (Exception ignored) {
            return true;
        }
    }

    static Integer maxActiveTasks(ComputeServer node) {
        try {
            JsonNode labels = labels(node);
            if (labels == null) return null;
            JsonNode value = labels.path(PLATFORM_MAX_ACTIVE_TASKS_LABEL);
            if (value.isMissingNode()) return null;
            int parsed = Integer.parseInt(value.asText());
            return parsed > 0 ? parsed : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    static String hardwareClass(ComputeServer node) {
        try {
            JsonNode labels = labels(node);
            if (labels == null) return null;
            String value = labels.path(HARDWARE_CLASS_LABEL).asText("").trim();
            return value.isEmpty() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonNode labels(ComputeServer node) throws Exception {
        String raw = node.getK8sLabelsJson();
        if (raw == null || raw.isBlank()) return null;
        JsonNode labels = new ObjectMapper().readTree(raw);
        return labels.isObject() ? labels : null;
    }
}
