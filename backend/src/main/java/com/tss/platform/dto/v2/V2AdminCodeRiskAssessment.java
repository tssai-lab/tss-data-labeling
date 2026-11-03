package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeRiskAssessment;

import java.time.Instant;

/** Sanitized risk evidence. Scanner error messages and storage details stay internal. */
public record V2AdminCodeRiskAssessment(
        String id,
        String versionId,
        String validationRunId,
        String artifactSha256,
        String riskPolicyVersion,
        String scannerVersion,
        String status,
        String riskLevel,
        String disposition,
        int findingCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    public static V2AdminCodeRiskAssessment from(CodeRiskAssessment assessment) {
        if (assessment == null) {
            return null;
        }
        return new V2AdminCodeRiskAssessment(
                assessment.getId(),
                assessment.getVersionId(),
                assessment.getValidationRunId(),
                assessment.getArtifactSha256(),
                assessment.getRiskPolicyVersion(),
                assessment.getScannerVersion(),
                assessment.getStatus(),
                assessment.getRiskLevel(),
                assessment.getDisposition(),
                assessment.getFindingCount() == null ? 0 : assessment.getFindingCount(),
                assessment.getCreatedAt(),
                assessment.getStartedAt(),
                assessment.getCompletedAt()
        );
    }
}
