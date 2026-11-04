package com.tss.platform.dto;

import lombok.Data;

@Data
public class CreateInferenceTaskRequest {
    private String name;
    private String modelVersionId;
    private String scriptVersionId;
    private String inputMode;
    private String datasetVersionId;
    private String inputObjectName;
    private String resourceProfileId;
    private String hardwareTargetId;
    private Long gpuMemoryLimitMiB;
    private Object params;
    private String remark;
}
