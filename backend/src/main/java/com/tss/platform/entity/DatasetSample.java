package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(
        name = "dataset_sample",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sample_version_external", columnNames = {"dataset_version_id", "external_id"}),
                @UniqueConstraint(name = "uk_sample_version_index", columnNames = {"dataset_version_id", "sample_index"})
        },
        indexes = {
                @Index(name = "idx_sample_version", columnList = "dataset_version_id"),
                @Index(name = "idx_sample_external", columnList = "external_id")
        }
)
public class DatasetSample {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Column(name = "created_by_package_id", length = 64)
    private String createdByPackageId;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "sample_index", nullable = false)
    private Integer sampleIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, Object> tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
