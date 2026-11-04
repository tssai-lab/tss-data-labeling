package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;

@Data
public class V2DatasetWorkspaceDto {
    private String workspaceId;
    private String datasetId;
    private V2DatasetVersionSummary baseVersion;
    private V2DatasetVersionSummary targetVersion;
    private String status;
    private Long workspaceRevision;
    private Long sampleCount;
    private V2DatasetActiveOperation activeOperation;
    private V2DatasetPublishReadiness publishReadiness;
    private List<String> availableActions;
    private V2UserError userError;
}
