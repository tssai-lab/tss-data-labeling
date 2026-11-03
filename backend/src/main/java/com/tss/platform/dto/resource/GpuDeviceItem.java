package com.tss.platform.dto.resource;

/** 单张物理 GPU 的实时身份和指标。 */
public record GpuDeviceItem(
        String hostGpuIndex,
        String uuid,
        String model,
        Long totalMemoryMiB,
        Long freeMemoryMiB,
        Double utilizationRate,
        Double temperatureCelsius
) {
}
