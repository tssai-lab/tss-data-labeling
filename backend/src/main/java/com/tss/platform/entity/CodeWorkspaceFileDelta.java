package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;

@Getter
@Setter
@Entity
@Table(
        name = "code_workspace_file_delta",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_code_workspace_file_delta_path",
                columnNames = {"workspace_id", "path"}
        ),
        indexes = @Index(
                name = "idx_code_workspace_file_delta_workspace_updated",
                columnList = "workspace_id,updated_at"
        )
)
public class CodeWorkspaceFileDelta {

    public static final String OPERATION_UPSERT = "UPSERT";
    public static final String OPERATION_DELETE = "DELETE";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(name = "path", nullable = false, length = 1024)
    private String path;

    @Column(name = "operation", nullable = false, length = 32)
    private String operation;

    @Column(name = "content_bytes", columnDefinition = "bytea")
    private byte[] contentBytes;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public byte[] getContentBytes() {
        return contentBytes == null ? null : Arrays.copyOf(contentBytes, contentBytes.length);
    }

    public void setContentBytes(byte[] contentBytes) {
        this.contentBytes = contentBytes == null
                ? null
                : Arrays.copyOf(contentBytes, contentBytes.length);
    }
}
