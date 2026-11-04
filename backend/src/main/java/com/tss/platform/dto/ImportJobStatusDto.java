package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class ImportJobStatusDto {
    private String importJobId;
    private String datasetVersionId;
    private String status;
    private Integer progress;
    private Integer totalSamples;
    private Integer importedSamples;
    private Integer failedSamples;
    private String errorCode;
    private String errorMessage;
    private String errorDetailsJson;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
