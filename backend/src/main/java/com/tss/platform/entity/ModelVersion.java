package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(
        name = "model_version",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_model_version_asset_version",
                        columnNames = {"asset_id", "version"}
                )
        }
)
public class ModelVersion {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

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

    @JsonIgnore
    @Column(name = "artifact_attested_sha256", length = 64)
    private String artifactAttestedSha256;

    @JsonIgnore
    @Column(name = "artifact_attested_at")
    private Instant artifactAttestedAt;

    @Column(name = "commit_info", length = 1024)
    private String commitInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hyper_params", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> hyperParams = Map.of();

    @Column(name = "description", length = 2048)
    private String description;

    @Column(name = "change_log")
    private String changeLog;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by")
    private Integer createdBy;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Transient
    @JsonProperty("isCurrent")
    private Boolean current;
}
