package com.tss.platform.entity;

import com.tss.platform.model.CodeApprovalDecisionSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "code_approval_record",
        indexes = {
                @Index(
                        name = "idx_code_approval_record_version_created",
                        columnList = "version_id,created_at,id"
                ),
                @Index(
                        name = "idx_code_approval_record_validation_run",
                        columnList = "validation_run_id"
                )
        }
)
public class CodeApprovalRecord {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;

    @Column(name = "validation_run_id", length = 64)
    private String validationRunId;

    @Column(name = "policy_version", length = 128)
    private String policyVersion;

    @Column(name = "decision", nullable = false, length = 32)
    private String decision = "PENDING";

    @Column(name = "decision_source", nullable = false, length = 32)
    private String decisionSource = CodeApprovalDecisionSource.ADMIN;

    @Column(name = "risk_assessment_id", length = 64)
    private String riskAssessmentId;

    @Column(name = "approval_policy_version", length = 128)
    private String approvalPolicyVersion;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "reviewer_user_id")
    private Integer reviewerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
