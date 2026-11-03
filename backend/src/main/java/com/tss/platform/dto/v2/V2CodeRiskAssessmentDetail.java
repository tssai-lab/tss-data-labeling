package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeRiskAssessment;

import java.time.Instant;
import java.util.List;

public record V2CodeRiskAssessmentDetail(
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
        String reasonCode,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<V2CodeRiskFinding> findings
) {

    public V2CodeRiskAssessmentDetail {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static V2CodeRiskAssessmentDetail from(
            CodeRiskAssessment assessment,
            List<V2CodeRiskFinding> findings
    ) {
        return new V2CodeRiskAssessmentDetail(
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
                reasonCode(assessment),
                assessment.getCreatedAt(),
                assessment.getStartedAt(),
                assessment.getCompletedAt(),
                findings
        );
    }

    private static String reasonCode(CodeRiskAssessment assessment) {
        if (assessment.getErrorCode() != null
                && assessment.getErrorCode().matches("[A-Z0-9_]+")) {
            return assessment.getErrorCode();
        }
        return switch (assessment.getDisposition() == null
                ? "" : assessment.getDisposition()) {
            case "AUTO_APPROVE" -> "RISK_LOW";
            case "MANUAL_REVIEW" -> "RISK_REVIEW_REQUIRED";
            case "BLOCK" -> "RISK_POLICY_BLOCKED";
            case "DIRECT_PASS" -> "REVIEW_BYPASSED_BY_SYSTEM_CONFIG";
            default -> "RISK_SCAN_IN_PROGRESS";
        };
    }
}
