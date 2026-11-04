package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeVersion;

import java.time.Instant;

/** Public immutable-version projection. Internal storage and ownership fields stay private. */
public record V2CodeVersionDto(
        String id,
        String assetId,
        String version,
        String fileName,
        Long sizeBytes,
        String status,
        String artifactSha256,
        String validationStatus,
        String validationPolicyVersion,
        String approvalStatus,
        String riskAssessmentId,
        String riskStatus,
        String riskLevel,
        String reviewDisposition,
        String riskPolicyVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant deprecatedAt,
        Instant archivedAt
) {

    public V2CodeVersionDto(
            String id,
            String assetId,
            String version,
            String fileName,
            Long sizeBytes,
            String status,
            String artifactSha256,
            String validationStatus,
            String validationPolicyVersion,
            String approvalStatus,
            Instant createdAt,
            Instant updatedAt,
            Instant deprecatedAt,
            Instant archivedAt
    ) {
        this(id, assetId, version, fileName, sizeBytes, status, artifactSha256,
                validationStatus, validationPolicyVersion, approvalStatus,
                null, null, null, null, null,
                createdAt, updatedAt, deprecatedAt, archivedAt);
    }

    public static V2CodeVersionDto from(CodeVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("Code version is required");
        }
        return new V2CodeVersionDto(
                version.getId(),
                version.getAssetId(),
                version.getVersion(),
                version.getFileName(),
                version.getSizeBytes(),
                version.getStatus(),
                version.getArtifactSha256(),
                version.getValidationStatus(),
                version.getValidationPolicyVersion(),
                version.getApprovalStatus(),
                version.getLatestRiskAssessmentId(),
                version.getRiskStatus(),
                version.getRiskLevel(),
                version.getReviewDisposition(),
                version.getRiskPolicyVersion(),
                version.getCreatedAt(),
                version.getUpdatedAt(),
                version.getDeprecatedAt(),
                version.getArchivedAt()
        );
    }
}
