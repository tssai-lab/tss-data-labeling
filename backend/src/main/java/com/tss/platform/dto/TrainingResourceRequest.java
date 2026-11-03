package com.tss.platform.dto;

import lombok.Data;

/** Optional per-run overrides. Missing values preserve the selected plan profile. */
@Data
public class TrainingResourceRequest {
    /** Opaque id returned by the detected hardware-options endpoint. */
    private String hardwareTargetId;
    private Double cpuCores;
    private Long memoryMiB;
    private Integer gpuCount;
    private Long gpuMemoryLimitMiB;
}
