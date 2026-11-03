package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@IdClass(DatasetVersionPackageId.class)
@Table(
        name = "dataset_version_package",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dvp_version_order",
                columnNames = {"dataset_version_id", "package_order"}
        ),
        indexes = {
                @Index(name = "idx_dvp_version", columnList = "dataset_version_id"),
                @Index(name = "idx_dvp_package", columnList = "package_id")
        }
)
public class DatasetVersionPackage {

    @Id
    @Column(name = "dataset_version_id", nullable = false, length = 64)
    private String datasetVersionId;

    @Id
    @Column(name = "package_id", nullable = false, length = 64)
    private String packageId;

    @Column(name = "package_role", nullable = false, length = 16)
    private String packageRole;

    @Column(name = "package_order", nullable = false)
    private Integer packageOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
