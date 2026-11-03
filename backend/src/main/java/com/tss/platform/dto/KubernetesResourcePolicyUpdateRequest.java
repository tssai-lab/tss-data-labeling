package com.tss.platform.dto;

public record KubernetesResourcePolicyUpdateRequest(
        Integer podQuota,
        Integer jobQuota,
        Integer jobTtlSecondsAfterFinished
) {
}
