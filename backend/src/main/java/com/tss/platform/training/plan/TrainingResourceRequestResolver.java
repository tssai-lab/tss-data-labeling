package com.tss.platform.training.plan;

import com.tss.platform.dto.TrainingResourceRequest;
import com.tss.platform.service.KubernetesQuantityParser;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将用户资源设置约束在所选方案规格内，并合并实际硬件选择器；不能由前端扩大方案上限。
 * CPU/内存/GPU 数量进入 Kubernetes 资源声明；显存预算传给训练运行时做软限制，
 * 不等同于 MIG 硬隔离，也不意味着 Kubernetes 已预留相应大小的显存。
 */
final class TrainingResourceRequestResolver {

    private static final long MIB = 1024L * 1024L;

    private TrainingResourceRequestResolver() {
    }

    static TrainingRunSpec.Resources resolve(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            TrainingResourceRequest request,
            String hardwareTargetId,
            Map<String, String> hardwareSelector,
            String gpuNodeName,
            String gpuHostIndex,
            String gpuUuid,
            String gpuModel,
            Long gpuTotalMemoryMiB
    ) {
        Map<String, String> resolvedSelector = new LinkedHashMap<>();
        if (profile.nodeSelector() != null) {
            resolvedSelector.putAll(profile.nodeSelector());
        }
        if (hardwareSelector != null) {
            hardwareSelector.forEach((key, value) -> {
                String existing = resolvedSelector.putIfAbsent(key, value);
                if (existing != null && !existing.equals(value)) {
                    throw new IllegalArgumentException("hardware target conflicts with the selected resource profile");
                }
            });
        }
        Map<String, String> nodeSelector = Collections.unmodifiableMap(resolvedSelector);
        int profileGpuCount = profile.gpuCount() == null ? -1 : profile.gpuCount();
        validateDeviceContract(runtime.deviceType(), profileGpuCount);

        if (request == null) {
            return new TrainingRunSpec.Resources(
                    profile.id(), hardwareTargetId, profile.cpuRequest(), profile.cpuLimit(),
                    profile.memoryRequest(), profile.memoryLimit(), profile.ephemeralStorageLimit(),
                    profileGpuCount, null, nodeSelector,
                    gpuNodeName, gpuHostIndex, gpuUuid, gpuModel, gpuTotalMemoryMiB
            );
        }

        String cpuRequest = profile.cpuRequest();
        String cpuLimit = profile.cpuLimit();
        if (request.getCpuCores() != null) {
            double selected = requireFinitePositive(request.getCpuCores(), "resourceRequest.cpuCores");
            double minimum = KubernetesQuantityParser.cpuCores(profile.cpuRequest());
            double maximum = KubernetesQuantityParser.cpuCores(profile.cpuLimit());
            requireRange(selected, minimum, maximum, "resourceRequest.cpuCores");
            cpuRequest = formatCpu(selected);
            cpuLimit = cpuRequest;
        }

        String memoryRequest = profile.memoryRequest();
        String memoryLimit = profile.memoryLimit();
        if (request.getMemoryMiB() != null) {
            long selected = request.getMemoryMiB();
            if (selected <= 0 || selected > Long.MAX_VALUE / MIB) {
                throw new IllegalArgumentException("resourceRequest.memoryMiB must be a positive integer");
            }
            long selectedBytes = selected * MIB;
            long minimum = KubernetesQuantityParser.memoryBytes(profile.memoryRequest());
            long maximum = KubernetesQuantityParser.memoryBytes(profile.memoryLimit());
            if (selectedBytes < minimum || selectedBytes > maximum) {
                throw new IllegalArgumentException("resourceRequest.memoryMiB is outside the selected profile bounds");
            }
            memoryRequest = selected + "Mi";
            memoryLimit = memoryRequest;
        }

        int gpuCount = request.getGpuCount() == null ? profileGpuCount : request.getGpuCount();
        if (gpuCount != profileGpuCount) {
            throw new IllegalArgumentException("resourceRequest.gpuCount must match the selected profile");
        }

        Long gpuMemoryLimitMiB = request.getGpuMemoryLimitMiB();
        if (gpuMemoryLimitMiB != null) {
            if (runtime.deviceType() != TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
                throw new IllegalArgumentException("resourceRequest.gpuMemoryLimitMiB is only valid for GPU training");
            }
            if (gpuMemoryLimitMiB <= 0) {
                throw new IllegalArgumentException("resourceRequest.gpuMemoryLimitMiB must be a positive integer");
            }
        }

        return new TrainingRunSpec.Resources(
                profile.id(), hardwareTargetId, cpuRequest, cpuLimit, memoryRequest, memoryLimit,
                profile.ephemeralStorageLimit(), gpuCount, gpuMemoryLimitMiB, nodeSelector,
                gpuNodeName, gpuHostIndex, gpuUuid, gpuModel, gpuTotalMemoryMiB
        );
    }

    static TrainingRunSpec.Resources resolve(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            TrainingResourceRequest request,
            String hardwareTargetId,
            Map<String, String> hardwareSelector
    ) {
        return resolve(runtime, profile, request, hardwareTargetId, hardwareSelector,
                null, null, null, null, null);
    }

    static TrainingRunSpec.Resources resolve(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            TrainingResourceRequest request
    ) {
        return resolve(runtime, profile, request, null, Map.of(),
                null, null, null, null, null);
    }

    private static void validateDeviceContract(
            TrainingPlanDefinition.DeviceType deviceType,
            int gpuCount
    ) {
        if (deviceType == TrainingPlanDefinition.DeviceType.NVIDIA_GPU && gpuCount != 1) {
            throw new IllegalArgumentException("NVIDIA_GPU profile must request exactly one GPU");
        }
        if (deviceType == TrainingPlanDefinition.DeviceType.CPU && gpuCount != 0) {
            throw new IllegalArgumentException("CPU profile cannot request a GPU");
        }
    }

    private static double requireFinitePositive(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + " must be a finite positive number");
        }
        return value;
    }

    private static void requireRange(double value, double minimum, double maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the selected profile bounds");
        }
    }

    private static String formatCpu(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
