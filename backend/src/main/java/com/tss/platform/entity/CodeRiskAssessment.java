package com.tss.platform.entity;

import com.tss.platform.model.CodeRiskAssessmentStatus;
import com.tss.platform.model.CodeRiskLevel;
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
        name = "code_risk_assessment",
        indexes = {
                @Index(
                        name = "idx_code_risk_assessment_version_created",
                        columnList = "version_id,created_at,id"
                ),
                @Index(
                        name = "idx_code_risk_assessment_status_created",
                        columnList = "status,created_at,id"
                )
        }
)
public class CodeRiskAssessment {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "validation_run_id", nullable = false, length = 64)
    private String validationRunId;

    @Column(name = "artifact_sha256", nullable = false, length = 64)
    private String artifactSha256;

    @Column(name = "risk_policy_version", nullable = false, length = 128)
    private String riskPolicyVersion;

    @Column(name = "scanner_version", nullable = false, length = 128)
    private String scannerVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status = CodeRiskAssessmentStatus.QUEUED;

    @Column(name = "risk_level", nullable = false, length = 32)
    private String riskLevel = CodeRiskLevel.UNKNOWN;

    @Column(name = "disposition", length = 32)
    private String disposition;

    @Column(name = "finding_count", nullable = false)
    private Integer findingCount = 0;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "requested_by_user_id")
    private Integer requestedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
