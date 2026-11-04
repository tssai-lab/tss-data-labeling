package com.tss.platform.inference;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.dto.InferenceHardwareOptionDto;
import com.tss.platform.dto.InferenceResourceProfileDto;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.service.ComputeServerSchedulingPolicy;
import com.tss.platform.service.GpuDeviceTargetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 提供 GPU 推理可选卡，并在创建任务时重新核对前端传回的短 ID。 */
@Service
public class InferenceHardwareOptionService {

    private final ComputeServerRepository serverRepository;
    private final GpuDeviceTargetService targetService;
    private final InferenceResourceProfileService profileService;
    private final InferenceModelCacheProperties modelCacheProperties;

    public InferenceHardwareOptionService(
            ComputeServerRepository serverRepository,
            GpuDeviceTargetService targetService,
            InferenceResourceProfileService profileService,
            InferenceModelCacheProperties modelCacheProperties
    ) {
        this.serverRepository = serverRepository;
        this.targetService = targetService;
        this.profileService = profileService;
        this.modelCacheProperties = modelCacheProperties;
    }

    @Transactional(readOnly = true)
    public List<InferenceHardwareOptionDto> listOptions() {
        if (!profileService.gpuInferenceEnabled()) return List.of();
        return targets().stream().map(target -> new InferenceHardwareOptionDto(
                scopedId(target),
                target.displayName(),
                target.nodeName(),
                target.hostGpuIndex(),
                target.uuid(),
                target.model(),
                target.totalMemoryMiB(),
                target.freeMemoryMiB(),
                target.utilizationRate(),
                target.temperatureCelsius(),
                target.observedAt(),
                "提交后按 UUID 等待这张卡，不会自动换卡"
        )).toList();
    }

    @Transactional(readOnly = true)
    public HardwareSelection requireForCreate(
            InferenceResourceProfileDto profile,
            String hardwareTargetId,
            Long gpuMemoryLimitMiB
    ) {
        boolean gpu = "NVIDIA_GPU".equals(profile.deviceType());
        if (!gpu) {
            if (normalize(hardwareTargetId) != null || gpuMemoryLimitMiB != null) {
                throw new IllegalArgumentException("CPU 推理不能指定 GPU 或 GPU 显存预算");
            }
            return HardwareSelection.cpu();
        }
        String requestedId = normalize(hardwareTargetId);
        if (requestedId == null) {
            throw new IllegalArgumentException("GPU 推理必须选择一张具体物理卡");
        }
        GpuDeviceTargetService.GpuDeviceTarget selected = targets().stream()
                .filter(target -> requestedId.equals(scopedId(target)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选 GPU 当前不可用或指标已过期"));
        if (gpuMemoryLimitMiB != null
                && (gpuMemoryLimitMiB <= 0 || gpuMemoryLimitMiB > selected.totalMemoryMiB())) {
            throw new IllegalArgumentException("GPU 显存软预算超出所选物理卡范围");
        }
        return new HardwareSelection(
                requestedId, selected.nodeName(), selected.hostGpuIndex(), selected.uuid(),
                selected.model(), selected.totalMemoryMiB(), gpuMemoryLimitMiB
        );
    }

    private List<GpuDeviceTargetService.GpuDeviceTarget> targets() {
        List<ComputeServer> candidates = serverRepository.findByDeletedFalse().stream()
                .filter(server -> "online".equals(server.getStatus()))
                .filter(server -> Boolean.TRUE.equals(server.getEnabled()))
                .filter(ComputeServerSchedulingPolicy::isPlatformSchedulable)
                .filter(ComputeServerSchedulingPolicy::isGpuSchedulable)
                .filter(server -> !modelCacheProperties.isEnabled()
                        || ComputeServerSchedulingPolicy.isCacheReady(server))
                .toList();
        return targetService.freshTargets(candidates);
    }

    private String scopedId(GpuDeviceTargetService.GpuDeviceTarget target) {
        return targetService.scopedTargetId("inference\n" + InferenceResourceProfileService.GPU_PROFILE_ID, target);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record HardwareSelection(
            String hardwareTargetId,
            String nodeName,
            String hostGpuIndex,
            String gpuUuid,
            String gpuModel,
            Long gpuTotalMemoryMiB,
            Long gpuMemoryLimitMiB
    ) {
        static HardwareSelection cpu() {
            return new HardwareSelection(null, null, null, null, null, null, null);
        }
    }
}
