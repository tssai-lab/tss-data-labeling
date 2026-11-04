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
        name = "code_risk_finding",
        indexes = @Index(
                name = "idx_code_risk_finding_assessment_location",
                columnList = "risk_assessment_id,file_path,line_start,id"
        )
)
public class CodeRiskFinding {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "risk_assessment_id", nullable = false, length = 64)
    private String riskAssessmentId;

    @Column(name = "rule_id", nullable = false, length = 128)
    private String ruleId;

    @Column(name = "severity", nullable = false, length = 32)
    private String severity;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "line_start")
    private Integer lineStart;

    @Column(name = "line_end")
    private Integer lineEnd;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
