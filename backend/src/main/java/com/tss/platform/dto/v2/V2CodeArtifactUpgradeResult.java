package com.tss.platform.dto.v2;

import java.util.Objects;

/** Public result of an administrator-triggered legacy code artifact upgrade. */
public record V2CodeArtifactUpgradeResult(
        String versionId,
        String artifactSha256,
        long sizeBytes,
        String approvalStatus,
        boolean upgraded,
        V2CodeValidationResult validation
) {
    public V2CodeArtifactUpgradeResult {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(approvalStatus, "approvalStatus");
        Objects.requireNonNull(validation, "validation");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
