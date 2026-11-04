package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeVersion;

import java.time.Instant;

/** Review metadata for one immutable artifact; no MinIO path or signed URL is exposed. */
public record V2AdminCodeReviewTaskDetail(
        String versionId,
        String assetId,
        String assetName,
        Integer ownerUserId,
        String version,
        String lifecycleStatus,
        String approvalStatus,
        String purpose,
        String runtime,
        String entryScript,
        String trainingType,
        String trainingProfile,
        String fileName,
        Long sizeBytes,
        String artifactSha256,
        String validationStatus,
        String validationPolicyVersion,
        Instant submittedAt,
        V2AdminCodeRiskAssessment riskAssessment
) {

    public static V2AdminCodeReviewTaskDetail from(
            CodeVersion version,
            CodeAsset asset,
            CodeRiskAssessment assessment
    ) {
        if (version == null || asset == null) {
            throw new IllegalArgumentException("Code review task scope is required");
        }
        return new V2AdminCodeReviewTaskDetail(
                version.getId(),
                asset.getId(),
                asset.getName(),
                version.getOwnerUserId(),
                version.getVersion(),
                version.getStatus(),
                version.getApprovalStatus(),
                version.getPurpose(),
                version.getRuntime(),
                version.getEntryScript(),
                version.getTrainingType(),
                version.getTrainingProfile(),
                version.getFileName(),
                version.getSizeBytes(),
                version.getArtifactSha256(),
                version.getValidationStatus(),
                version.getValidationPolicyVersion(),
                version.getCreatedAt(),
                V2AdminCodeRiskAssessment.from(assessment)
        );
    }
}
