package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "import_job_sample_failure",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ijsf_job_external",
                columnNames = {"import_job_id", "external_id"}
        ),
        indexes = {
                @Index(name = "idx_ijsf_job_status", columnList = "import_job_id,status"),
                @Index(name = "idx_ijsf_version_package_status", columnList = "dataset_version_id,package_id,status")
        }
)
public class ImportJobSampleFailure {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "import_job_id", nullable = false, length = 64)
    private String importJobId;

    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Column(name = "package_id", length = 64)
    private String packageId;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "sample_index", nullable = false)
    private Integer sampleIndex;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_details_json", columnDefinition = "TEXT")
    private String errorDetailsJson;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
