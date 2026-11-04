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
        name = "dataset_annotation",
        indexes = {
                @Index(name = "idx_anno_sample", columnList = "sample_id"),
                @Index(name = "idx_anno_sd", columnList = "sample_data_id"),
                @Index(name = "idx_anno_version", columnList = "dataset_version_id")
        }
)
public class DatasetAnnotation {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "sample_id", nullable = false, length = 64)
    private String sampleId;

    @Column(name = "sample_data_id", length = 64)
    private String sampleDataId;

    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Column(name = "package_id", length = 64)
    private String packageId;

    @Column(name = "annotation_type", nullable = false, length = 64)
    private String annotationType;

    @Column(name = "format", nullable = false, length = 32)
    private String format;

    @Column(name = "original_path", nullable = false, length = 1024)
    private String originalPath;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Column(name = "zip_entry_offset")
    private Long zipEntryOffset;

    @Column(name = "zip_data_offset")
    private Long zipDataOffset;

    @Column(name = "compressed_size")
    private Long compressedSize;

    @Column(name = "uncompressed_size")
    private Long uncompressedSize;

    @Column(name = "compression_method", length = 16)
    private String compressionMethod;

    @Column(name = "crc32")
    private Long crc32;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
