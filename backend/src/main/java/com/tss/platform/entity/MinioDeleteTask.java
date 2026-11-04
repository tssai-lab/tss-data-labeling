package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "minio_delete_task",
        indexes = {
                @Index(name = "idx_minio_delete_task_status", columnList = "status"),
                @Index(name = "idx_minio_delete_task_object", columnList = "bucket, object_name"),
                @Index(name = "idx_minio_delete_task_source", columnList = "source_type, source_id")
        }
)
public class MinioDeleteTask {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "bucket", nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_name", nullable = false, length = 1024)
    private String objectName;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount = 5;

    @Column(name = "failed_reset_count", nullable = false)
    private Integer failedResetCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
