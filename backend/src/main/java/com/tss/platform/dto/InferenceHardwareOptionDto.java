package com.tss.platform.dto;

import java.time.Instant;

/** 页面可选择的一张真实物理 GPU；UUID 用于绑定，主机编号用于认卡。 */
public record InferenceHardwareOptionDto(
        String hardwareTargetId,
        String displayName,
        String nodeName,
        String hostGpuIndex,
        String uuid,
        String model,
        Long totalMemoryMiB,
        Long freeMemoryMiB,
        Double utilizationRate,
        Double temperatureCelsius,
        Instant observedAt,
        String message
) {
}
