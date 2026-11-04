package com.tss.platform.dto.v2;

import com.tss.platform.service.CodeValidationResult;

/** Stable validation evidence returned by workspace and version validation endpoints. */
public record V2CodeValidationResult(
        String policyVersion,
        String artifactSha256,
        String status,
        String reasonCode,
        String message,
        int fileCount,
        boolean reused
) {
    public V2CodeValidationResult(
            String policyVersion,
            String artifactSha256,
            String status,
            String reasonCode,
            String message,
            int fileCount
    ) {
        this(
                policyVersion,
                artifactSha256,
                status,
                reasonCode,
                message,
                fileCount,
                false
        );
    }

    public static V2CodeValidationResult from(CodeValidationResult result) {
        return new V2CodeValidationResult(
                result.policyVersion(),
                result.artifactSha256(),
                result.status(),
                result.reasonCode(),
                result.safeMessage(),
                result.fileCount(),
                result.reused()
        );
    }
}
