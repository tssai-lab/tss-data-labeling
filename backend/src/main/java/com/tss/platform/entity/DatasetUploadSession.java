package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(
        name = "dataset_upload_session",
        indexes = {
                @Index(name = "idx_dataset_upload_fingerprint", columnList = "file_fingerprint"),
                @Index(name = "idx_dataset_upload_status", columnList = "status")
        }
)
public class DatasetUploadSession {

    @Id
    @Column(name = "id", length = 96)
    private String id;

    @Column(name = "file_fingerprint", length = 512)
    private String fileFingerprint;

    @Column(name = "upload_purpose", nullable = false, length = 32)
    private String uploadPurpose = "INITIAL_DATASET";

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(name = "dataset_name", nullable = false, length = 255)
    private String datasetName;

    @Column(name = "dataset_version", nullable = false, length = 64)
    private String version;

    @Column(name = "version_label", length = 64)
    private String versionLabel;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "version_label_generated", nullable = false)
    private Boolean versionLabelGenerated = false;

    @Column(name = "task_type", nullable = false, length = 16)
    private String type;

    @Column(name = "cv_task_type", length = 64)
    private String cvTaskType;

    @Column(name = "annotation_format", length = 64)
    private String annotationFormat;

    @Column(name = "remark", length = 1024)
    private String remark;

    @Column(name = "description", length = 2048)
    private String description;

    @Column(name = "change_log")
    private String changeLog;

    @Column(name = "parent_version_id", length = 64)
    private String parentVersionId;

    @Column(name = "sample_grouping", length = 32)
    private String sampleGrouping;

    @Column(name = "manifest_path", length = 255)
    private String manifestPath;

    @Column(name = "strict_manifest", nullable = false)
    private Boolean strictManifest = false;

    @Column(name = "import_job_id", length = 64)
    private String importJobId;

    @Column(name = "asset_created_by_upload", nullable = false)
    private Boolean assetCreatedByUpload = false;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "completion_error_code", length = 64)
    private String completionErrorCode;

    @Column(name = "completion_error_message", length = 512)
    private String completionErrorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completion_error_details", columnDefinition = "jsonb")
    private Map<String, Object> completionErrorDetails;

    @Column(name = "completion_failed_at")
    private Instant completionFailedAt;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "artifact_spec_id", length = 128)
    private String artifactSpecId;

    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Column(name = "version_id", length = 64)
    private String versionId;

    @Column(name = "workspace_base_revision")
    private Long workspaceBaseRevision;

    @Column(name = "target_kind", length = 32)
    private String targetKind;

    @Column(name = "target_operation", length = 16)
    private String targetOperation;

    @Column(name = "target_sample_id", length = 64)
    private String targetSampleId;

    @Column(name = "target_resource_id", length = 64)
    private String targetResourceId;

    @Column(name = "expected_sha256", length = 64)
    private String expectedSha256;

    @Column(name = "declared_format", length = 32)
    private String declaredFormat;

    @Column(name = "declared_content_type", length = 128)
    private String declaredContentType;

    @Column(name = "target_data_type", length = 32)
    private String targetDataType;

    @Column(name = "target_sensor", length = 64)
    private String targetSensor;

    @Column(name = "target_channel", length = 32)
    private String targetChannel;

    @Column(name = "target_seq")
    private Integer targetSeq;

    @Column(name = "target_sample_data_id", length = 64)
    private String targetSampleDataId;

    @Column(name = "target_annotation_type", length = 64)
    private String targetAnnotationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_metadata", columnDefinition = "jsonb")
    private Map<String, Object> targetMetadata;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
