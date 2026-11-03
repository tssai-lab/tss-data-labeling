package com.tss.platform.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record SystemConfigUpdateRequest(
        String trainingCodeReviewMode,
        @JsonAlias({"userLogStorageLimitMb"})
        Integer logMaxSize
) {
}
