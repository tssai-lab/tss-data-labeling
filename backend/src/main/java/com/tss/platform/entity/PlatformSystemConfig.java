package com.tss.platform.entity;

import com.tss.platform.model.TrainingCodeReviewMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "platform_system_config")
public class PlatformSystemConfig {

    public static final String GLOBAL_ID = "GLOBAL";
    public static final int DEFAULT_USER_LOG_LIMIT_MB = 50;
    public static final int MIN_USER_LOG_LIMIT_MB = 1;
    public static final int MAX_USER_LOG_LIMIT_MB = 10240;
    public static final int DEFAULT_POD_QUOTA = 10;
    public static final int MIN_POD_QUOTA = 1;
    public static final int MAX_POD_QUOTA = 50;
    public static final int DEFAULT_JOB_QUOTA = 20;
    public static final int MIN_JOB_QUOTA = 1;
    public static final int MAX_JOB_QUOTA = 200;
    public static final int DEFAULT_JOB_TTL_SECONDS_AFTER_FINISHED = 3600;
    public static final int MIN_JOB_TTL_SECONDS_AFTER_FINISHED = 60;
    public static final int MAX_JOB_TTL_SECONDS_AFTER_FINISHED = 3600;
    public static final long GIB = 1024L * 1024 * 1024;
    public static final long DEFAULT_MODEL_CACHE_MAX_BYTES = GIB;
    public static final long DEFAULT_MODEL_CACHE_MIN_FREE_BYTES = 3L * GIB;
    public static final long DEFAULT_MODEL_CACHE_RUNTIME_RESERVE_BYTES = 10L * GIB;
    public static final long MIN_MODEL_CACHE_POLICY_BYTES = GIB;
    public static final long MAX_MODEL_CACHE_POLICY_BYTES = 1024L * GIB;

    @Id
    @Column(name = "id", length = 32)
    private String id = GLOBAL_ID;

    @Column(name = "training_code_review_mode", nullable = false, length = 32)
    private String trainingCodeReviewMode = TrainingCodeReviewMode.STANDARD_REVIEW.name();

    /** 每个用户日志存储上限（MB） */
    @Column(name = "operation_log_max_size")
    private Integer operationLogMaxSize = DEFAULT_USER_LOG_LIMIT_MB;

    @Column(name = "pod_quota", nullable = false)
    private Integer podQuota = DEFAULT_POD_QUOTA;

    @Column(name = "job_quota", nullable = false)
    private Integer jobQuota = DEFAULT_JOB_QUOTA;

    @Column(name = "job_ttl_seconds_after_finished", nullable = false)
    private Integer jobTtlSecondsAfterFinished = DEFAULT_JOB_TTL_SECONDS_AFTER_FINISHED;

    @Column(name = "model_cache_max_bytes", nullable = false)
    private Long modelCacheMaxBytes = DEFAULT_MODEL_CACHE_MAX_BYTES;

    @Column(name = "model_cache_min_free_bytes", nullable = false)
    private Long modelCacheMinFreeBytes = DEFAULT_MODEL_CACHE_MIN_FREE_BYTES;

    @Column(name = "model_cache_runtime_reserve_bytes", nullable = false)
    private Long modelCacheRuntimeReserveBytes = DEFAULT_MODEL_CACHE_RUNTIME_RESERVE_BYTES;

    @Column(name = "updated_by_user_id")
    private Integer updatedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
