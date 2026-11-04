package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class DatasetWorkspaceAuditLogDto {
    private String id;
    private String datasetAssetId;
    private String datasetVersionId;
    private String parentVersionId;
    private String operation;
    private String actorType;
    private Integer actorUserId;
    private String targetType;
    private String targetId;
    private String importJobId;
    private String packageId;
    private String sampleId;
    private Map<String, Object> details;
    private Instant createdAt;
}
