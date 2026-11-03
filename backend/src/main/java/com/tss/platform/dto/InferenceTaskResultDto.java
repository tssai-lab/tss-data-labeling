package com.tss.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
public class InferenceTaskResultDto {
    private String id;
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
}
