package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-controlled resource limits used by the enabled inference profile. */
@Getter
@Setter
@ConfigurationProperties(prefix = "inference.kubernetes")
public class InferenceKubernetesResourceProperties {

    private String cpuRequest = "500m";
    private String cpuLimit = "2";
    private String memoryRequest = "512Mi";
    private String memoryLimit = "4Gi";
    private String ephemeralStorageRequest = "2Gi";
    private String ephemeralStorageLimit = "12Gi";

    /** GPU 推理沿用单卡模型，CPU/内存仍由后端白名单控制。 */
    private String gpuCpuRequest = "1";
    private String gpuCpuLimit = "4";
    private String gpuMemoryRequest = "2Gi";
    private String gpuMemoryLimit = "8Gi";
    private String gpuEphemeralStorageRequest = "2Gi";
    private String gpuEphemeralStorageLimit = "12Gi";
}
