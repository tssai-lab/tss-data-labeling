package com.tss.platform.entity;

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
        name = "code_workspace",
        indexes = {
                @Index(
                        name = "idx_code_workspace_owner_deleted_updated",
                        columnList = "owner_user_id,deleted,updated_at"
                ),
                @Index(
                        name = "idx_code_workspace_asset_status_deleted",
                        columnList = "asset_id,status,deleted"
                )
        }
)
public class CodeWorkspace {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Column(name = "base_version_id", length = 64)
    private String baseVersionId;

    @Column(name = "closed_version_id", length = 64)
    private String closedVersionId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_OPEN;

    @Column(name = "revision", nullable = false)
    private Long revision = 0L;

    @Column(name = "owner_user_id", nullable = false)
    private Integer ownerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
