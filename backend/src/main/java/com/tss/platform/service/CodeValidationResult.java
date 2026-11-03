package com.tss.platform.service;

import java.util.Objects;

public record CodeValidationResult(
        String policyVersion,
        String artifactSha256,
        String status,
        String reasonCode,
        String safeMessage,
        int fileCount,
        boolean reused
) {
    public CodeValidationResult(
            String policyVersion,
            String artifactSha256,
            String status,
            String reasonCode,
            String safeMessage,
            int fileCount
    ) {
        this(
                policyVersion,
                artifactSha256,
                status,
                reasonCode,
                safeMessage,
                fileCount,
                false
        );
    }

    public CodeValidationResult {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(safeMessage, "safeMessage");
        if (fileCount < 0) {
            throw new IllegalArgumentException("fileCount must not be negative");
        }
    }

    public boolean passed() {
        return "PASSED".equals(status);
    }

    public CodeValidationResult asReused() {
        if (reused) {
            return this;
        }
        return new CodeValidationResult(
                policyVersion,
                artifactSha256,
                status,
                reasonCode,
                safeMessage,
                fileCount,
                true
        );
    }
}
