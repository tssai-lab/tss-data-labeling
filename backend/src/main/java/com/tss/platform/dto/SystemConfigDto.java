package com.tss.platform.dto;

import java.time.Instant;

public record SystemConfigDto(
        String trainingCodeReviewMode,
        Integer logMaxSize,
        Integer userLogStorageLimitMb,
        Instant updatedAt
) {
}
