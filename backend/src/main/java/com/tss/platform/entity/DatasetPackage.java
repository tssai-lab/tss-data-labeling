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

@Getter
@Setter
@Entity
@Table(
        name = "dataset_package",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dataset_package_storage_path",
                columnNames = "storage_path"
        ),
        indexes = {
                @Index(name = "idx_dataset_package_asset", columnList = "dataset_asset_id"),
                @Index(name = "idx_dataset_package_deleted", columnList = "deleted")
        }
)
public class DatasetPackage {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "dataset_asset_id", nullable = false, length = 64)
    private String datasetAssetId;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Column(name = "manifest_path", length = 255)
    private String manifestPath;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "storage_kind", nullable = false, length = 16)
    private String storageKind = "ZIP";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
