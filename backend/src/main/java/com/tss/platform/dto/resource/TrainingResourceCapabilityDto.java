package com.tss.platform.dto.resource;

import com.tss.platform.training.plan.TrainingPlanDefinition;

import java.time.Instant;
import java.util.List;

/** Aggregate resource facts for task creation; contains no node or device identity. */
public record TrainingResourceCapabilityDto(
        String planId,
        String planVersion,
        String resourceProfileId,
        TrainingPlanDefinition.DeviceType deviceType,
        CpuBounds cpu,
        MemoryBounds memory,
        Integer gpuCount,
        Integer eligibleNodeCount,
        boolean capacityAvailable,
        GpuCapability gpu,
        DataStatus dataStatus,
        Instant observedAt,
        String message
) {
    public record CpuBounds(double requestCores, double limitCores) {
    }

    public record MemoryBounds(long requestMiB, long limitMiB) {
    }

    public record GpuCapability(
            List<String> models,
            int observedGpuCount,
            Long safeTotalMemoryMiB,
            Long maxFreeMemoryMiB,
            boolean metricsComplete
    ) {
    }

    public enum DataStatus {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE
    }
}
