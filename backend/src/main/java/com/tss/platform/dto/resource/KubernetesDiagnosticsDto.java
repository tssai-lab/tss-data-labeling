package com.tss.platform.dto.resource;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class KubernetesDiagnosticsDto {
    private String collectionStatus;
    private String message;
    private Instant collectedAt;
    private String configuredInferenceImage;
    private List<KubernetesNodeHealth> nodes = new ArrayList<>();
    private List<KubernetesPodIssue> podIssues = new ArrayList<>();
    private List<KubernetesWorkloadImage> workloadImages = new ArrayList<>();

    @Data
    public static class KubernetesNodeHealth {
        private String name;
        private Boolean ready;
        private Boolean unschedulable;
        private Boolean memoryPressure;
        private Boolean diskPressure;
        private Boolean pidPressure;
        private String healthStatus;
        private String message;
    }

    @Data
    public static class KubernetesPodIssue {
        private String namespace;
        private String podName;
        private String nodeName;
        private String phase;
        private String containerType;
        private String containerName;
        private String reason;
        private String message;
        private Integer exitCode;
    }

    @Data
    public static class KubernetesWorkloadImage {
        private String namespace;
        private String podName;
        private String nodeName;
        private String workloadType;
        private String containerType;
        private String containerName;
        private String declaredImage;
        private String imageId;
        private Boolean configuredInferenceImageMatch;
    }
}
