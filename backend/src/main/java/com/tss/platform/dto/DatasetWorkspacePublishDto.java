package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DatasetWorkspacePublishDto {
    private String datasetVersionId;
    private String parentVersionId;
    private String datasetAssetId;
    private Integer versionNo;
    private String versionLabel;
    private String status;
    private Instant publishedAt;
    private String currentVersionId;
    private String message;
}
