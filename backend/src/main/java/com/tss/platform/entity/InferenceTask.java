package com.tss.platform.entity;

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
@Table(name = "inference_task")
public class InferenceTask {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "model_version_id", nullable = false, length = 64)
    private String modelVersionId;

    @Column(name = "script_version_id", nullable = false, length = 64)
    private String scriptVersionId;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode;

    @Column(name = "dataset_version_id", length = 64)
    private String datasetVersionId;

    @Column(name = "input_object_name", length = 1024)
    private String inputObjectName;

    @Column(name = "resource_profile_id", length = 64)
    private String resourceProfileId;

    @Column(name = "hardware_target_id", length = 64)
    private String hardwareTargetId;

    @Column(name = "gpu_node_name", length = 255)
    private String gpuNodeName;

    @Column(name = "gpu_host_index", length = 16)
    private String gpuHostIndex;

    @Column(name = "gpu_uuid", length = 128)
    private String gpuUuid;

    @Column(name = "gpu_model", length = 255)
    private String gpuModel;

    @Column(name = "gpu_total_memory_mib")
    private Long gpuTotalMemoryMiB;

    @Column(name = "gpu_memory_limit_mib")
    private Long gpuMemoryLimitMiB;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "server_ip", length = 45)
    private String serverIp;

    @Column(name = "queue_sort_index")
    private Integer queueSortIndex = 0;

    @Column(name = "priority", length = 8)
    private String priority = "中";

    @Column(name = "progress")
    private Integer progress;

    @Column(name = "current_attempt", nullable = false)
    private Integer currentAttempt = 1;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "log_path", length = 1024)
    private String logPath;

    @Column(name = "output_path", length = 1024)
    private String outputPath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "remark", length = 1024)
    private String remark;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
