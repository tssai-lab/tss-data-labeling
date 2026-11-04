package com.tss.platform.dto;

/** Read-only inference resource profile exposed to task creation clients. */
public record InferenceResourceProfileDto(
        String id,
        String displayName,
        String description,
        String deviceType,
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit,
        String ephemeralStorageRequest,
        String ephemeralStorageLimit,
        Integer gpuCount
) {
}
