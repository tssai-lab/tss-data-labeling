package com.tss.platform.dto;

import lombok.Data;

@Data
public class CreateExperimentVersionRequest {
    /** 同次提交和超时重试共用；旧客户端可不传。 */
    private String submissionKey;
    private String name;
    /** 基础模型权重版本（API 别名，落库到 modelVersionId） */
    private String baseModelVersionId;
    private String modelVersionId;
    private String codeVersionId;
    private String datasetVersionId;
    private String planId;
    private String planVersion;
    private String trainingMode;
    private String resourceProfileId;
    private TrainingResourceRequest resourceRequest;
    private Object hyperParams;
    private Object params;
    private String remark;
}
