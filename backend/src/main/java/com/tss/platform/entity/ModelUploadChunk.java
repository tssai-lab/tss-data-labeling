package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "model_upload_chunk",
        indexes = {
                @Index(name = "idx_model_upload_chunk_session", columnList = "upload_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_model_upload_chunk_part",
                        columnNames = {"upload_id", "part_index"}
                )
        }
)
public class ModelUploadChunk {

    @Id
    @Column(name = "id", length = 96)
    private String id;

    @Column(name = "upload_id", nullable = false, length = 96)
    private String uploadId;

    @Column(name = "part_index", nullable = false)
    private Integer partIndex;

    @Column(name = "object_name", nullable = false, length = 1024)
    private String objectName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "created_at")
    private Instant createdAt;
}
