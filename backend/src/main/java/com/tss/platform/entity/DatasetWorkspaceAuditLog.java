package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
        name = "dataset_workspace_audit_log",
        indexes = {
                @Index(
                        name = "idx_workspace_audit_version_created",
                        columnList = "dataset_version_id, created_at, id"
                ),
                @Index(
                        name = "idx_workspace_audit_asset_created",
                        columnList = "dataset_asset_id, created_at, id"
                ),
                @Index(name = "idx_workspace_audit_import_job", columnList = "import_job_id"),
                @Index(name = "idx_workspace_audit_operation", columnList = "operation")
        }
)
public class DatasetWorkspaceAuditLog {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "dataset_asset_id", nullable = false, length = 64)
    private String datasetAssetId;

    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Column(name = "parent_version_id", length = 64)
    private String parentVersionId;

    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(name = "actor_user_id")
    private Integer actorUserId;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "import_job_id", length = 64)
    private String importJobId;

    @Column(name = "package_id", length = 64)
    private String packageId;

    @Column(name = "sample_id", length = 64)
    private String sampleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
