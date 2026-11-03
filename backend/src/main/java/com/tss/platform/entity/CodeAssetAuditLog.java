package com.tss.platform.entity;

import com.tss.platform.model.CodeAuditActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "code_asset_audit_log",
        indexes = {
                @Index(
                        name = "idx_code_asset_audit_log_asset_created",
                        columnList = "asset_id,created_at,id"
                ),
                @Index(
                        name = "idx_code_asset_audit_log_version_created",
                        columnList = "version_id,created_at,id"
                ),
                @Index(
                        name = "idx_code_asset_audit_log_workspace_created",
                        columnList = "workspace_id,created_at,id"
                )
        }
)
public class CodeAssetAuditLog {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Column(name = "version_id", length = 64)
    private String versionId;

    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private Integer actorUserId;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType = CodeAuditActorType.USER;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
