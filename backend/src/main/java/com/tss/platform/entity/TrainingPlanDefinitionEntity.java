package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "training_plan_definition")
public class TrainingPlanDefinitionEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "plan_version", nullable = false, length = 16)
    private String planVersion;

    @Column(name = "schema_version", nullable = false, length = 64)
    private String schemaVersion;

    @Column(name = "yaml_content", nullable = false, columnDefinition = "TEXT")
    private String yamlContent;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "imported_by_user_id", nullable = false)
    private Integer importedByUserId;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "published_by_user_id", nullable = false)
    private Integer publishedByUserId;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "disabled_by_user_id")
    private Integer disabledByUserId;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
