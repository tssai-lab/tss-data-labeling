package com.tss.platform.inference;

import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.InferenceResourceProfileDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Server-side whitelist for inference resources.
 *
 * CPU 始终可用；精确选卡和 kubectl 客户端同时启用后，才开放单卡 GPU 规格。
 */
@Service
public class InferenceResourceProfileService {

    public static final String DEFAULT_PROFILE_ID = "cpu-small";
    public static final String GPU_PROFILE_ID = "gpu-one";

    private final InferenceKubernetesResourceProperties properties;
    private final TrainingKubernetesProperties kubernetesProperties;

    public InferenceResourceProfileService(InferenceKubernetesResourceProperties properties) {
        this(properties, new TrainingKubernetesProperties());
    }

    @Autowired
    public InferenceResourceProfileService(
            InferenceKubernetesResourceProperties properties,
            TrainingKubernetesProperties kubernetesProperties
    ) {
        this.properties = properties;
        this.kubernetesProperties = kubernetesProperties;
    }

    public List<InferenceResourceProfileDto> listEnabledProfiles() {
        if (gpuInferenceEnabled()) {
            return List.of(cpuSmall(), gpuOne());
        }
        return List.of(cpuSmall());
    }

    /** Missing profile IDs from old clients deliberately use the safe CPU default. */
    public InferenceResourceProfileDto resolveForCreate(String requestedProfileId) {
        String profileId = normalize(requestedProfileId);
        return resolve(profileId == null ? DEFAULT_PROFILE_ID : profileId);
    }

    /** Historical rows have null and retain the former global-resource behaviour. */
    public InferenceResourceProfileDto resolveForExecution(String storedProfileId) {
        String profileId = normalize(storedProfileId);
        return resolve(profileId == null ? DEFAULT_PROFILE_ID : profileId);
    }

    private InferenceResourceProfileDto resolve(String profileId) {
        if (GPU_PROFILE_ID.equals(profileId) && gpuInferenceEnabled()) {
            return gpuOne();
        }
        if (!DEFAULT_PROFILE_ID.equals(profileId)) {
            throw new IllegalArgumentException("不支持的推理资源规格: " + profileId);
        }
        return cpuSmall();
    }

    private InferenceResourceProfileDto cpuSmall() {
        return new InferenceResourceProfileDto(
                DEFAULT_PROFILE_ID,
                "CPU 小型",
                "当前 CPU 推理的默认资源规格",
                "CPU",
                properties.getCpuRequest(),
                properties.getCpuLimit(),
                properties.getMemoryRequest(),
                properties.getMemoryLimit(),
                properties.getEphemeralStorageRequest(),
                properties.getEphemeralStorageLimit(),
                0
        );
    }

    private InferenceResourceProfileDto gpuOne() {
        return new InferenceResourceProfileDto(
                GPU_PROFILE_ID,
                "GPU 单卡",
                "按 UUID 绑定一张指定的物理 GPU",
                "NVIDIA_GPU",
                properties.getGpuCpuRequest(),
                properties.getGpuCpuLimit(),
                properties.getGpuMemoryRequest(),
                properties.getGpuMemoryLimit(),
                properties.getGpuEphemeralStorageRequest(),
                properties.getGpuEphemeralStorageLimit(),
                1
        );
    }

    public boolean gpuInferenceEnabled() {
        return kubernetesProperties.isExactGpuSelectionEnabled()
                && kubernetesProperties.getClientMode()
                == TrainingKubernetesProperties.ClientMode.KUBECTL;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
