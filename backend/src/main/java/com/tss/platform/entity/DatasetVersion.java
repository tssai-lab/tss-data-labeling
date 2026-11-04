package com.tss.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "dataset_version",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_dataset_version_asset_version",
                        columnNames = {"asset_id", "version"}
                )
        }
)
public class DatasetVersion {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "version_label", length = 64)
    private String versionLabel;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "storage_path", length = 1024)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String storagePath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;

    @Column(name = "artifact_spec_id", length = 128)
    private String artifactSpecId;

    @Column(name = "file_count")
    private Long fileCount;

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

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Column(name = "active_draft_asset_id", length = 64)
    private String activeDraftAssetId;

    @Column(name = "file_fingerprint", length = 512)
    private String fileFingerprint;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by")
    private Integer createdBy;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "workspace_revision", nullable = false)
    private Long workspaceRevision = 0L;

    @JsonIgnore
    @Column(name = "workspace_head_version_id", length = 64)
    private String workspaceHeadVersionId;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    @PreUpdate
    void updateActiveDraftAssetId() {
        activeDraftAssetId = "DRAFT".equals(status) && Boolean.FALSE.equals(deleted)
                ? assetId
                : null;
    }
}
