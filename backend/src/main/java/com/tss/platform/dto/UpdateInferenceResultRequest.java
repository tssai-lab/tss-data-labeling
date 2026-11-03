package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class UpdateInferenceResultRequest {
    private String status;
    private Integer progress;
    private Object result;
    private String logPath;
    private String outputPath;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
}
