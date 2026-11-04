package com.tss.platform.dto.v2;

public record V2CodeApprovalRequest(
        String decision,
        String reason,
        String expectedValidationRunId,
        String expectedRiskAssessmentId,
        String expectedArtifactSha256,
        String expectedPolicyVersion
) {

    public V2CodeApprovalRequest(String decision, String reason) {
        this(decision, reason, null, null, null, null);
    }
}
