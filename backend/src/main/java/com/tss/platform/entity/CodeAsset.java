package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "code_asset")
public class CodeAsset {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "training_profile", length = 128)
    private String trainingProfile;

    @Column(name = "purpose", length = 1024)
    private String purpose;

    @Column(name = "runtime", length = 128)
    private String runtime;

    @Column(name = "entry_script", length = 1024)
    private String entryScript;

    @Column(name = "training_type", length = 128)
    private String trainingType;

    @Column(name = "remark", length = 1024)
    private String remark;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    /** 演示资产：对所有用户可见、只读 */
    @Column(name = "is_demo", nullable = false)
    private Boolean isDemo = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
