package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeVersion;

import java.time.Instant;

/** Administrator queue projection without persistence or object-storage details. */
public record V2AdminCodeReviewTask(
        String versionId,
        String assetId,
        String assetName,
        Integer ownerUserId,
        String version,
        String lifecycleStatus,
        String approvalStatus,
        String artifactSha256,
        String validationStatus,
        String validationPolicyVersion,
        String riskAssessmentId,
        String riskStatus,
        String riskLevel,
        String reviewDisposition,
        String riskPolicyVersion,
        int findingCount,
        Instant submittedAt
) {

    public static V2AdminCodeReviewTask from(
            CodeVersion version,
            CodeAsset asset,
            CodeRiskAssessment assessment
    ) {
        if (version == null || asset == null) {
            throw new IllegalArgumentException("Code review task scope is required");
        }
        return new V2AdminCodeReviewTask(
                version.getId(),
                asset.getId(),
                asset.getName(),
                version.getOwnerUserId(),
                version.getVersion(),
                version.getStatus(),
                version.getApprovalStatus(),
                version.getArtifactSha256(),
                version.getValidationStatus(),
                version.getValidationPolicyVersion(),
                version.getLatestRiskAssessmentId(),
                version.getRiskStatus(),
                version.getRiskLevel(),
                version.getReviewDisposition(),
                version.getRiskPolicyVersion(),
                assessment == null || assessment.getFindingCount() == null
                        ? 0 : assessment.getFindingCount(),
                version.getCreatedAt()
        );
    }
}
