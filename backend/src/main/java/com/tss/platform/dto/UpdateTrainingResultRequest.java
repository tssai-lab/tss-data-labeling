package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class UpdateTrainingResultRequest {
    private String status;
    private Integer progress;
    private Object metrics;
    private String runId;
    private String mlflowExperimentId;
    private String mlflowTrackingUri;
    private String logPath;
    private String outputPath;
    private TrainingModelArtifactDto modelArtifact;
    private Object trainingOutput;
    private String trainingOutputSha256;
    private String trainingOutputObjectName;
    private Long trainingOutputSizeBytes;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private String remark;
}
