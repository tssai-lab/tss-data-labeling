package com.tss.platform.entity;

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
        name = "code_validation_run",
        indexes = @Index(
                name = "idx_code_validation_run_version_created",
                columnList = "version_id,created_at,id"
        )
)
public class CodeValidationRun {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "artifact_sha256", nullable = false, length = 64)
    private String artifactSha256;

    @Column(name = "policy_version", nullable = false, length = 128)
    private String policyVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "NOT_RUN";

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "requested_by_user_id", nullable = false)
    private Integer requestedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
