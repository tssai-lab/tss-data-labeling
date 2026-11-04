package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.TrainingResourceRequest;
import com.tss.platform.dto.resource.TrainingHardwareOptionDto;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Discovers concrete hardware models without exposing node, IP or device identity. */
@Service
public class TrainingHardwareOptionService {

    private static final long MIB = 1024L * 1024L;
    private static final String AUTOMATIC_CLASS = "__automatic__";

    private final TrainingPlanRegistry planRegistry;
    private final ComputeServerRepository serverRepository;
    private final GpuDeviceObservationStore gpuObservationStore;
    private final GpuDeviceTargetService gpuDeviceTargetService;
    private final InferenceModelCacheProperties modelCacheProperties;
    private final TrainingKubernetesProperties kubernetesProperties;

    public TrainingHardwareOptionService(
            TrainingPlanRegistry planRegistry,
            ComputeServerRepository serverRepository,
            GpuDeviceObservationStore gpuObservationStore,
            GpuDeviceTargetService gpuDeviceTargetService,
            InferenceModelCacheProperties modelCacheProperties,
            TrainingKubernetesProperties kubernetesProperties
    ) {
        this.planRegistry = planRegistry;
        this.serverRepository = serverRepository;
        this.gpuObservationStore = gpuObservationStore;
        this.gpuDeviceTargetService = gpuDeviceTargetService;
        this.modelCacheProperties = modelCacheProperties;
        this.kubernetesProperties = kubernetesProperties;
    }

    @Transactional(readOnly = true)
    public List<TrainingHardwareOptionDto> options(String planId, String planVersion) {
        TrainingPlanDefinition plan = planRegistry.requireEnabled(planId, planVersion);
        List<TrainingHardwareOptionDto> result = new ArrayList<>();
        for (TrainingPlanDefinition.RuntimeVariant runtime : plan.runtimes()) {
            for (TrainingPlanDefinition.ResourceProfile profile : runtime.resourceProfiles()) {
                groupsFor(plan, runtime, profile).stream()
                        .map(DetectedGroup::option)
                        .forEach(result::add);
            }
        }
        result.sort(Comparator
                .comparing((TrainingHardwareOptionDto item) ->
                        item.deviceType() == TrainingPlanDefinition.DeviceType.CPU ? 0 : 1)
                .thenComparing(TrainingHardwareOptionDto::displayName)
                .thenComparing(TrainingHardwareOptionDto::resourceProfileId));
        return List.copyOf(result);
    }

    /** Resolve the opaque target again at submission time; callers never provide a selector. */
    @Transactional(readOnly = true)
    public HardwareSelection requireSelection(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            TrainingResourceRequest request
    ) {
        String requestedId = request == null ? null : trimToNull(request.getHardwareTargetId());
        if (requestedId == null) {
            if (kubernetesProperties.isExactGpuSelectionEnabled()
                    && runtime.deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
                throw new IllegalArgumentException("GPU 训练必须选择一张具体物理卡");
            }
            return HardwareSelection.legacyAutomatic();
        }

        DetectedGroup selected = groupsFor(plan, runtime, profile).stream()
                .filter(group -> requestedId.equals(group.option().hardwareTargetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "所选硬件型号当前不可用、指标已过期或与训练方案不兼容"));
        validateRequestedCapacity(selected, profile, request);
        GpuDeviceTargetService.GpuDeviceTarget target = selected.gpuTarget();
        return new HardwareSelection(
                requestedId,
                selected.nodeSelector(),
                target == null ? null : target.nodeName(),
                target == null ? null : target.hostGpuIndex(),
                target == null ? null : target.uuid(),
                target == null ? null : target.model(),
                target == null ? null : target.totalMemoryMiB()
        );
    }

    private List<DetectedGroup> groupsFor(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile
    ) {
        double minimumCpu = KubernetesQuantityParser.cpuCores(profile.cpuRequest());
        long minimumMemoryMiB = toMiB(profile.memoryRequest());
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
                .filter(server -> hasCapacity(server, minimumCpu, minimumMemoryMiB, gpuCount))
                .toList();

        if (runtime.deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
            if (kubernetesProperties.isExactGpuSelectionEnabled()) {
                if (kubernetesProperties.getClientMode()
                        != TrainingKubernetesProperties.ClientMode.KUBECTL) {
                    return List.of();
                }
                return exactGpuGroups(plan, runtime, profile, candidates);
            }
            return gpuGroups(plan, runtime, profile, candidates);
        }
        return cpuGroups(plan, runtime, profile, candidates);
    }

    private List<DetectedGroup> cpuGroups(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            List<ComputeServer> candidates
    ) {
        Map<String, List<ComputeServer>> byClass = new LinkedHashMap<>();
        for (ComputeServer candidate : candidates) {
            String hardwareClass = firstText(
                    ComputeServerSchedulingPolicy.hardwareClass(candidate), AUTOMATIC_CLASS);
            byClass.computeIfAbsent(hardwareClass, ignored -> new ArrayList<>()).add(candidate);
        }

        List<DetectedGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ComputeServer>> entry : byClass.entrySet()) {
            // An empty selector cannot isolate unlabelled nodes from labelled pools.
            if (AUTOMATIC_CLASS.equals(entry.getKey()) && byClass.size() > 1) continue;
            List<ComputeServer> nodes = List.copyOf(entry.getValue());
            Set<String> detectedModels = new LinkedHashSet<>();
            nodes.stream().map(ComputeServer::getSpecCpu)
                    .map(TrainingHardwareOptionService::trimToNull)
                    .filter(Objects::nonNull)
                    .forEach(detectedModels::add);
            String displayName = detectedModels.size() == 1
                    ? detectedModels.iterator().next()
                    : "CPU 计算资源";
            groups.add(buildGroup(
                    plan, runtime, profile, entry.getKey(), displayName,
                    nodes, List.of(), null, null));
        }
        return groups;
    }

    private List<DetectedGroup> gpuGroups(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            List<ComputeServer> candidates
    ) {
        Instant now = Instant.now();
        Map<String, List<ComputeServer>> candidatesByClass = new LinkedHashMap<>();
        for (ComputeServer candidate : candidates) {
            String hardwareClass = firstText(
                    ComputeServerSchedulingPolicy.hardwareClass(candidate), AUTOMATIC_CLASS);
            candidatesByClass.computeIfAbsent(hardwareClass, ignored -> new ArrayList<>())
                    .add(candidate);
        }
        List<ObservedNode> observed = new ArrayList<>();
        for (ComputeServer candidate : candidates) {
            var observation = gpuObservationStore.fresh(candidate.getServerIp(), now)
                    .or(() -> gpuObservationStore.fresh(candidate.getK8sNodeName(), now));
            if (observation.isEmpty()) continue;
            Set<String> models = new LinkedHashSet<>();
            observation.get().devices().stream()
                    .map(GpuDeviceObservationStore.DeviceObservation::modelName)
                    .map(TrainingHardwareOptionService::trimToNull)
                    .filter(Objects::nonNull)
                    .forEach(models::add);
            int requiredGpu = profile.gpuCount() == null ? 0 : profile.gpuCount();
            int declaredGpu = candidate.getGpuCount() == null ? 0 : candidate.getGpuCount();
            if (models.size() != 1
                    || observation.get().devices().size() < requiredGpu
                    || observation.get().devices().size() < declaredGpu) continue;
            observed.add(new ObservedNode(
                    candidate,
                    firstText(ComputeServerSchedulingPolicy.hardwareClass(candidate), AUTOMATIC_CLASS),
                    models.iterator().next(),
                    observation.get().devices(),
                    observation.get().observedAt()
            ));
        }

        Map<String, Set<String>> modelsByClass = new LinkedHashMap<>();
        for (ObservedNode node : observed) {
            modelsByClass.computeIfAbsent(node.hardwareClass(), ignored -> new LinkedHashSet<>())
                    .add(node.model());
        }

        Map<String, List<ObservedNode>> grouped = new LinkedHashMap<>();
        for (ObservedNode node : observed) {
            // One selector class must never represent multiple GPU models. Without a class,
            // automatic selection is safe only while it represents the entire candidate set.
            List<ComputeServer> classCandidates = candidatesByClass.getOrDefault(
                    node.hardwareClass(), List.of());
            long observedClassNodes = observed.stream()
                    .filter(value -> value.hardwareClass().equals(node.hardwareClass()))
                    .count();
            if (modelsByClass.getOrDefault(node.hardwareClass(), Set.of()).size() != 1
                    || observedClassNodes != classCandidates.size()
                    || (AUTOMATIC_CLASS.equals(node.hardwareClass())
                    && candidatesByClass.size() > 1)) continue;
            String key = node.hardwareClass() + "\u0000" + normalize(node.model());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }

        List<DetectedGroup> groups = new ArrayList<>();
        for (List<ObservedNode> values : grouped.values()) {
            ObservedNode first = values.get(0);
            List<ComputeServer> nodes = values.stream().map(ObservedNode::server).toList();
            List<GpuDeviceObservationStore.DeviceObservation> devices = values.stream()
                    .flatMap(value -> value.devices().stream())
                    .toList();
            Instant observedAt = values.stream().map(ObservedNode::observedAt)
                    .min(Comparator.naturalOrder()).orElse(null);
            groups.add(buildGroup(
                    plan, runtime, profile, first.hardwareClass(), first.model(),
                    nodes, devices, observedAt, null));
        }
        return groups;
    }

    private List<DetectedGroup> exactGpuGroups(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            List<ComputeServer> candidates
    ) {
        return gpuDeviceTargetService.freshTargets(candidates).stream()
                .map(target -> buildGroup(
                        plan,
                        runtime,
                        profile,
                        firstText(ComputeServerSchedulingPolicy.hardwareClass(target.server()), AUTOMATIC_CLASS),
                        target.displayName(),
                        List.of(target.server()),
                        List.of(new GpuDeviceObservationStore.DeviceObservation(
                                target.hostGpuIndex(), target.uuid(), target.model(),
                                target.totalMemoryMiB(), target.freeMemoryMiB(),
                                target.utilizationRate(), target.temperatureCelsius())),
                        target.observedAt(),
                        target
                ))
                .toList();
    }

    private DetectedGroup buildGroup(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            String hardwareClass,
            String displayName,
            List<ComputeServer> nodes,
            List<GpuDeviceObservationStore.DeviceObservation> devices,
            Instant observedAt,
            GpuDeviceTargetService.GpuDeviceTarget exactTarget
    ) {
        double profileCpuLimit = KubernetesQuantityParser.cpuCores(profile.cpuLimit());
        long profileMemoryLimitMiB = toMiB(profile.memoryLimit());
        // A pool selector may land on any member. Conservative minima guarantee that every
        // combination offered by the UI fits at least the declared capacity of every member.
        double detectedCpuLimit = nodes.stream().map(ComputeServer::getCpuCores)
                .filter(Objects::nonNull).min(Double::compareTo).orElse(0.0);
        long detectedMemoryLimitMiB = nodes.stream().map(ComputeServer::getMemoryGib)
                .filter(Objects::nonNull)
                .map(value -> Math.max(0L, (long) Math.floor(value * 1024.0)))
                .min(Long::compareTo).orElse(0L);
        double cpuLimit = Math.min(profileCpuLimit, detectedCpuLimit);
        long memoryLimitMiB = Math.min(profileMemoryLimitMiB, detectedMemoryLimitMiB);

        boolean gpuMemoryComplete = !devices.isEmpty() && devices.stream()
                .allMatch(device -> device.totalMemoryMiB() != null
                        && device.totalMemoryMiB() > 0);
        Long safeTotal = gpuMemoryComplete ? devices.stream()
                .map(GpuDeviceObservationStore.DeviceObservation::totalMemoryMiB)
                .min(Long::compareTo).orElse(null) : null;
        Long maxFree = devices.stream()
                .map(GpuDeviceObservationStore.DeviceObservation::freeMemoryMiB)
                .filter(value -> value != null && value >= 0)
                .max(Long::compareTo).orElse(null);
        boolean gpu = runtime.deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU;
        String targetId = exactTarget == null
                ? targetId(plan, profile, hardwareClass, displayName)
                : gpuDeviceTargetService.scopedTargetId(
                        String.join("\n", plan.id(), plan.version(), profile.id()), exactTarget);
        Map<String, String> selector = exactTarget != null
                ? Map.of("kubernetes.io/hostname", exactTarget.nodeName())
                : AUTOMATIC_CLASS.equals(hardwareClass)
                        ? Map.of()
                        : Map.of(ComputeServerSchedulingPolicy.HARDWARE_CLASS_LABEL, hardwareClass);
        TrainingHardwareOptionDto.GpuCapability gpuCapability = gpu
                ? new TrainingHardwareOptionDto.GpuCapability(
                        exactTarget == null ? displayName : exactTarget.model(),
                        devices.size(), safeTotal, maxFree, gpuMemoryComplete,
                        exactTarget == null ? null : exactTarget.hostGpuIndex(),
                        exactTarget == null ? null : exactTarget.uuid(),
                        exactTarget == null ? null : exactTarget.nodeName(),
                        exactTarget == null ? null : exactTarget.utilizationRate(),
                        exactTarget == null ? null : exactTarget.temperatureCelsius())
                : null;
        TrainingHardwareOptionDto option = new TrainingHardwareOptionDto(
                targetId,
                displayName,
                profile.id(),
                runtime.deviceType(),
                new TrainingHardwareOptionDto.CpuBounds(
                        KubernetesQuantityParser.cpuCores(profile.cpuRequest()), cpuLimit),
                new TrainingHardwareOptionDto.MemoryBounds(
                        toMiB(profile.memoryRequest()), memoryLimitMiB),
                profile.gpuCount(),
                nodes.size(),
                gpuCapability,
                TrainingHardwareOptionDto.DataStatus.AVAILABLE,
                observedAt,
                exactTarget != null
                        ? "将按 GPU UUID 绑定这张物理卡；编号是该卡当前在宿主机上的编号"
                        : gpu
                        ? "硬件型号和 GPU 指标已检测；空闲显存仅供参考，提交时资源仍可能变化"
                        : "硬件资源已检测；提交时可用容量仍可能变化"
        );
        return new DetectedGroup(option, selector, nodes, exactTarget);
    }

    private void validateRequestedCapacity(
            DetectedGroup group,
            TrainingPlanDefinition.ResourceProfile profile,
            TrainingResourceRequest request
    ) {
        double cpu = request.getCpuCores() == null
                ? KubernetesQuantityParser.cpuCores(profile.cpuRequest())
                : request.getCpuCores();
        long memoryMiB = request.getMemoryMiB() == null
                ? toMiB(profile.memoryRequest())
                : request.getMemoryMiB();
        int gpu = request.getGpuCount() == null
                ? (profile.gpuCount() == null ? 0 : profile.gpuCount())
                : request.getGpuCount();
        if (group.nodes().stream().noneMatch(node -> hasCapacity(node, cpu, memoryMiB, gpu))) {
            throw new IllegalArgumentException("所选硬件型号没有节点能满足本次 CPU、内存和 GPU 组合");
        }
        Long gpuMemoryLimitMiB = request.getGpuMemoryLimitMiB();
        Long safeTotal = group.option().gpu() == null
                ? null : group.option().gpu().safeTotalMemoryMiB();
        if (gpuMemoryLimitMiB != null && (safeTotal == null || gpuMemoryLimitMiB > safeTotal)) {
            throw new IllegalArgumentException("resourceRequest.gpuMemoryLimitMiB exceeds selected hardware memory");
        }
    }

    private boolean hasCapacity(ComputeServer server, double cpu, long memoryMiB, int gpu) {
        double availableCpu = server.getCpuCores() == null ? 0 : server.getCpuCores();
        double availableMemoryMiB = server.getMemoryGib() == null ? 0 : server.getMemoryGib() * 1024.0;
        int availableGpu = server.getGpuCount() == null ? 0 : server.getGpuCount();
        return availableCpu >= cpu && availableMemoryMiB >= memoryMiB && availableGpu >= gpu;
    }

    private long toMiB(String quantity) {
        long bytes = KubernetesQuantityParser.memoryBytes(quantity);
        return (bytes + MIB - 1) / MIB;
    }

    private String targetId(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.ResourceProfile profile,
            String hardwareClass,
            String displayName
    ) {
        String source = String.join("\n", plan.id(), plan.version(), profile.id(),
                hardwareClass, normalize(displayName));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "hw-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String firstText(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    public record HardwareSelection(
            String hardwareTargetId,
            Map<String, String> nodeSelector,
            String gpuNodeName,
            String gpuHostIndex,
            String gpuUuid,
            String gpuModel,
            Long gpuTotalMemoryMiB
    ) {
        public HardwareSelection {
            nodeSelector = nodeSelector == null ? Map.of() : Map.copyOf(nodeSelector);
        }

        static HardwareSelection legacyAutomatic() {
            return new HardwareSelection(null, Map.of(), null, null, null, null, null);
        }
    }

    private record DetectedGroup(
            TrainingHardwareOptionDto option,
            Map<String, String> nodeSelector,
            List<ComputeServer> nodes,
            GpuDeviceTargetService.GpuDeviceTarget gpuTarget
    ) {
    }

    private record ObservedNode(
            ComputeServer server,
            String hardwareClass,
            String model,
            List<GpuDeviceObservationStore.DeviceObservation> devices,
            Instant observedAt
    ) {
    }
}
