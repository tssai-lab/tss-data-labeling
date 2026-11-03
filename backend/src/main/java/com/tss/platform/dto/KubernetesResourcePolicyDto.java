package com.tss.platform.dto;

import java.time.Instant;

public record KubernetesResourcePolicyDto(
        int podQuota,
        int jobQuota,
        int jobTtlSecondsAfterFinished,
        int usedPods,
        int usedJobs,
        String clusterName,
        String namespace,
        Instant updatedAt
) {
}
