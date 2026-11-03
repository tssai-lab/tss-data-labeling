package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "dataset_asset")
public class DatasetAsset {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", length = 64)
    private String type;

    @Column(name = "cv_task_type", length = 64)
    private String cvTaskType;

    @Column(name = "annotation_format", length = 64)
    private String annotationFormat;

    @Column(name = "remark", length = 1024)
    private String remark;

    @Column(name = "current_version_id", length = 64)
    private String currentVersionId;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    /** 演示资产：对所有用户可见、只读 */
    @Column(name = "is_demo", nullable = false)
    private Boolean isDemo = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}

