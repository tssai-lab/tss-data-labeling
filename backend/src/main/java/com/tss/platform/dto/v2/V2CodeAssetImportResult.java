package com.tss.platform.dto.v2;

import java.time.Instant;

public record V2CodeAssetImportResult(
        String assetId,
        String versionId,
        String version,
        String fileName,
        long sizeBytes,
        String trainingProfile,
        String status,
        String validationStatus,
        String validationPolicyVersion,
        String approvalStatus,
        String artifactSha256,
        Instant createdAt
) {
}
