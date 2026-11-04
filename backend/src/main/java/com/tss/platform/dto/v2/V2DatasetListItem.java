package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;

@Data
public class V2DatasetListItem {
    private String datasetId;
    private String name;
    private String type;
    private V2DatasetVersionSummary currentVersion;
    private Long currentVersionFileCount;
    private Long fileCount;
    private String artifactSpecId;
    private String displayStatus;
    private Boolean hasDraft;
    private String workspaceId;
    private Long workspaceRevision;
    private V2DatasetPublishReadiness publishReadiness;
    private V2DatasetEditability editability;
    private Integer importProgress;
    private List<String> availableActions;
    private V2UserError userError;
    /** 演示资产：全局共享、只读 */
    private Boolean isDemo;
}
