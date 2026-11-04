package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "training_experiment_version",
        indexes = {
                @Index(name = "idx_training_experiment_id", columnList = "experiment_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_training_experiment_version",
                        columnNames = {"experiment_id", "version_no"}
                )
        }
)
public class TrainingExperimentVersion {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 每次保存核对修订号，拒绝用旧快照覆盖停止、完成或结果发布。 */
    @Version
    @Column(name = "lock_revision", nullable = false)
    private Long lockRevision;

    @Column(name = "experiment_id", nullable = false, length = 64)
    private String experimentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "model_version_id", length = 64)
    private String modelVersionId;

    @Column(name = "code_version_id", nullable = false, length = 128)
    private String codeVersionId;

    @Column(name = "training_profile", length = 128)
    private String trainingProfile;

    @Column(name = "training_plan_id", length = 64, updatable = false)
    private String trainingPlanId;

    @Column(name = "training_plan_version", length = 32, updatable = false)
    private String trainingPlanVersion;

    @Column(name = "training_mode", length = 32, updatable = false)
    private String trainingMode;

    @Column(name = "resource_profile_id", length = 64, updatable = false)
    private String resourceProfileId;

    @Column(name = "run_spec_json", columnDefinition = "TEXT", updatable = false)
    private String runSpecJson;

    @Column(name = "run_spec_sha256", length = 64, updatable = false)
    private String runSpecSha256;

    @Column(name = "input_model_sha256", length = 64, updatable = false)
    private String inputModelSha256;

    @Column(name = "input_dataset_sha256", length = 64, updatable = false)
    private String inputDatasetSha256;

    @Column(name = "input_code_sha256", length = 64, updatable = false)
    private String inputCodeSha256;

    @Column(name = "code_approval_record_id", length = 64, updatable = false)
    private String codeApprovalRecordId;

    @Column(name = "runtime_image", length = 512, updatable = false)
    private String runtimeImage;

    @Column(name = "runtime_image_digest", length = 71, updatable = false)
    private String runtimeImageDigest;

    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Column(name = "hyper_params_json", columnDefinition = "TEXT")
    private String hyperParamsJson;

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

    @Column(name = "metrics_json", columnDefinition = "TEXT")
    private String metricsJson;

    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "mlflow_experiment_id", length = 64)
    private String mlflowExperimentId;

    @Column(name = "mlflow_tracking_uri", length = 512)
    private String mlflowTrackingUri;

    @Column(name = "log_path", length = 1024)
    private String logPath;

    @Column(name = "output_path", length = 1024)
    private String outputPath;

    @Column(name = "produced_model_version_id", length = 64)
    private String producedModelVersionId;

    @Column(name = "model_publish_status", length = 32)
    private String modelPublishStatus;

    @Column(name = "model_publish_error", columnDefinition = "TEXT")
    private String modelPublishError;

    @Column(name = "model_published_at")
    private Instant modelPublishedAt;

    @Column(name = "model_artifact_path", length = 1024)
    private String modelArtifactPath;

    @Column(name = "model_artifact_sha256", length = 64)
    private String modelArtifactSha256;

    @Column(name = "model_artifact_size_bytes")
    private Long modelArtifactSizeBytes;

    @Column(name = "training_output_json", columnDefinition = "TEXT")
    private String trainingOutputJson;

    @Column(name = "training_output_sha256", length = 64)
    private String trainingOutputSha256;

    @Column(name = "training_output_object_name", length = 1024)
    private String trainingOutputObjectName;

    @Column(name = "training_output_size_bytes")
    private Long trainingOutputSizeBytes;

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
