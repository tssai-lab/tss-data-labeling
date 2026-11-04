package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetWorkspaceDraftDto {
    private String draftVersionId;
    private String parentVersionId;
    private String datasetAssetId;
    private Integer versionNo;
    private String status;
    private String currentVersionId;
    private String message;
}

