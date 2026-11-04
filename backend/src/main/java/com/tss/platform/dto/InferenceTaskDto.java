package com.tss.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
public class InferenceTaskDto {
    private String id;
    private String name;
    private String modelVersionId;
    private String scriptVersionId;
    private String inputMode;
    private String datasetVersionId;
    private String inputObjectName;
    private String resourceProfileId;
    private String hardwareTargetId;
    private String gpuNodeName;
    private String gpuHostIndex;
    private String gpuUuid;
    private String gpuModel;
    private Long gpuTotalMemoryMiB;
    private Long gpuMemoryLimitMiB;
    private JsonNode params;
    private String status;
    private Integer progress;
    private Integer currentAttempt;
    private Integer retryCount;
    private Integer maxRetries;
    private Boolean retryable;
    private Instant lastRetryAt;
    private JsonNode result;
    private String logPath;
    private String outputPath;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private String remark;
    private Integer ownerUserId;
    private Instant createdAt;
    private Instant updatedAt;
}
