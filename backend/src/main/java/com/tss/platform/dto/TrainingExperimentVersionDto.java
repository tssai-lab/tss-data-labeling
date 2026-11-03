package com.tss.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
public class TrainingExperimentVersionDto {
    private String id;
    private String experimentId;
    private Integer versionNo;
    private String name;
    private String modelVersionId;
    /** 与 modelVersionId 相同，供前端展示“基础模型权重” */
    private String baseModelVersionId;
    private String codeVersionId;
    private String trainingProfile;
    private String trainingPlanId;
    private String trainingPlanVersion;
    private String trainingMode;
    private String resourceProfileId;
    private JsonNode runSpec;
    private String runSpecSha256;
    private String inputModelSha256;
    private String inputDatasetSha256;
    private String inputCodeSha256;
    private String codeApprovalRecordId;
    private String runtimeImage;
    private String runtimeImageDigest;
    private String datasetVersionId;
    private JsonNode hyperParams;
    private String status;
    private Integer progress;
    private JsonNode metrics;
    private String runId;
    private String mlflowExperimentId;
    private String mlflowTrackingUri;
    private String logPath;
    private String outputPath;
    private String producedModelVersionId;
    private String modelPublishStatus;
    private String modelPublishError;
    private Instant modelPublishedAt;
    private String modelArtifactPath;
    private String modelArtifactSha256;
    private Long modelArtifactSizeBytes;
    private JsonNode trainingOutput;
    private String trainingOutputSha256;
    private String trainingOutputObjectName;
    private Long trainingOutputSizeBytes;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private String remark;
    private Integer ownerUserId;
    private Instant createdAt;
    private Instant updatedAt;

    public String getCreateTime() {
        return createdAt != null ? createdAt.toString() : null;
    }
}
