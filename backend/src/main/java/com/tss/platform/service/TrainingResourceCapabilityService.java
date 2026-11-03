package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.dto.resource.TrainingResourceCapabilityDto;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TrainingResourceCapabilityService {

    private static final long MIB = 1024L * 1024L;

    private final TrainingPlanRegistry planRegistry;
    private final ComputeServerRepository serverRepository;
    private final GpuDeviceObservationStore gpuObservationStore;
    private final InferenceModelCacheProperties modelCacheProperties;

    public TrainingResourceCapabilityService(
            TrainingPlanRegistry planRegistry,
            ComputeServerRepository serverRepository,
            GpuDeviceObservationStore gpuObservationStore,
            InferenceModelCacheProperties modelCacheProperties
    ) {
        this.planRegistry = planRegistry;
        this.serverRepository = serverRepository;
        this.gpuObservationStore = gpuObservationStore;
        this.modelCacheProperties = modelCacheProperties;
    }

    @Transactional(readOnly = true)
    public TrainingResourceCapabilityDto capability(
            String planId,
            String planVersion,
            String resourceProfileId
    ) {
        TrainingPlanDefinition plan = planRegistry.requireEnabled(planId, planVersion);
        TrainingPlanRegistry.ResolvedRuntime resolved = planRegistry.resolveRuntime(plan, resourceProfileId);
        TrainingPlanDefinition.RuntimeVariant runtime = resolved.runtime();
        TrainingPlanDefinition.ResourceProfile profile = resolved.resourceProfile();

        double cpuRequest = KubernetesQuantityParser.cpuCores(profile.cpuRequest());
        double cpuLimit = KubernetesQuantityParser.cpuCores(profile.cpuLimit());
        long memoryRequestMiB = toMiB(profile.memoryRequest());
        long memoryLimitMiB = toMiB(profile.memoryLimit());
        int gpuCount = profile.gpuCount() == null ? 0 : profile.gpuCount();

        List<ComputeServer> candidates = serverRepository.findByDeletedFalse().stream()
                .filter(server -> "online".equals(server.getStatus()))
                .filter(server -> Boolean.TRUE.equals(server.getEnabled()))
                .filter(ComputeServerSchedulingPolicy::isPlatformSchedulable)
                .filter(server -> ComputeServerSchedulingPolicy.matchesNodeSelector(
                        server, profile.nodeSelector()))
                .filter(server -> runtime.deviceType() != TrainingPlanDefinition.DeviceType.NVIDIA_GPU
                        || ComputeServerSchedulingPolicy.isGpuSchedulable(server))
                .filter(server -> !modelCacheProperties.isEnabled()
                        || ComputeServerSchedulingPolicy.isCacheReady(server))
                .filter(server -> hasCapacity(server, cpuRequest, memoryRequestMiB, gpuCount))
                .toList();

        if (runtime.deviceType() != TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
            boolean available = !candidates.isEmpty();
            return new TrainingResourceCapabilityDto(
                    plan.id(), plan.version(), profile.id(), runtime.deviceType(),
                    new TrainingResourceCapabilityDto.CpuBounds(cpuRequest, cpuLimit),
                    new TrainingResourceCapabilityDto.MemoryBounds(memoryRequestMiB, memoryLimitMiB),
                    gpuCount, candidates.size(), available, null,
                    available ? TrainingResourceCapabilityDto.DataStatus.AVAILABLE
                            : TrainingResourceCapabilityDto.DataStatus.UNAVAILABLE,
                    null,
                    available ? "检测到符合训练方案的 CPU 节点；提交时资源仍可能变化"
                            : "当前没有符合训练方案的 CPU 节点"
            );
        }

        Instant now = Instant.now();
        List<GpuDeviceObservationStore.DeviceObservation> devices = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        List<Instant> observedTimes = new ArrayList<>();
        int observedNodes = 0;
        int expectedDevices = 0;
        for (ComputeServer server : candidates) {
            expectedDevices += Math.max(0, server.getGpuCount() == null ? 0 : server.getGpuCount());
            var observation = gpuObservationStore.fresh(server.getServerIp(), now)
                    .or(() -> gpuObservationStore.fresh(server.getK8sNodeName(), now));
            if (observation.isEmpty()) {
                if (server.getSpecGpu() != null && !server.getSpecGpu().isBlank()) {
                    models.add(server.getSpecGpu().trim());
                }
                continue;
            }
            observedNodes++;
            observedTimes.add(observation.get().observedAt());
            devices.addAll(observation.get().devices());
            observation.get().devices().stream()
                    .map(GpuDeviceObservationStore.DeviceObservation::modelName)
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(models::add);
        }

        Long safeTotal = devices.stream()
                .map(GpuDeviceObservationStore.DeviceObservation::totalMemoryMiB)
                .filter(value -> value != null && value > 0)
                .min(Long::compareTo)
                .orElse(null);
        Long maxFree = devices.stream()
                .map(GpuDeviceObservationStore.DeviceObservation::freeMemoryMiB)
                .filter(value -> value != null && value >= 0)
                .max(Long::compareTo)
                .orElse(null);
        boolean metricsComplete = !candidates.isEmpty()
                && observedNodes == candidates.size()
                && devices.size() >= expectedDevices
                && safeTotal != null;
        TrainingResourceCapabilityDto.DataStatus status = candidates.isEmpty()
                ? TrainingResourceCapabilityDto.DataStatus.UNAVAILABLE
                : metricsComplete
                ? TrainingResourceCapabilityDto.DataStatus.AVAILABLE
                : TrainingResourceCapabilityDto.DataStatus.PARTIAL;
        Instant observedAt = observedTimes.stream().min(Comparator.naturalOrder()).orElse(null);
        String message = switch (status) {
            case AVAILABLE -> "GPU 详情采集完整；空闲显存仅供参考，提交时资源仍可能变化";
            case PARTIAL -> "存在符合方案的 GPU 节点，但部分显卡详情缺失或已过期，暂不允许设置显存预算";
            case UNAVAILABLE -> "当前没有符合训练方案且允许平台调度的 GPU 节点";
        };

        return new TrainingResourceCapabilityDto(
                plan.id(), plan.version(), profile.id(), runtime.deviceType(),
                new TrainingResourceCapabilityDto.CpuBounds(cpuRequest, cpuLimit),
                new TrainingResourceCapabilityDto.MemoryBounds(memoryRequestMiB, memoryLimitMiB),
                gpuCount, candidates.size(), !candidates.isEmpty(),
                new TrainingResourceCapabilityDto.GpuCapability(
                        List.copyOf(models), devices.size(), safeTotal, maxFree, metricsComplete),
                status, observedAt, message
        );
    }

    private boolean hasCapacity(
            ComputeServer server,
            double cpuRequest,
            long memoryRequestMiB,
            int gpuCount
    ) {
        double cpu = server.getCpuCores() == null ? 0 : server.getCpuCores();
        double memoryMiB = server.getMemoryGib() == null ? 0 : server.getMemoryGib() * 1024.0;
        int gpu = server.getGpuCount() == null ? 0 : server.getGpuCount();
        return cpu >= cpuRequest && memoryMiB >= memoryRequestMiB && gpu >= gpuCount;
    }

    private long toMiB(String quantity) {
        long bytes = KubernetesQuantityParser.memoryBytes(quantity);
        return (bytes + MIB - 1) / MIB;
    }
}
