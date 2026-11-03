package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;

@Data
public class V2ImportJobStatusDto {
    private String importJobId;
    private String workspaceId;
    private Long workspaceRevision;
    private String status;
    private String displayStatus;
    private Integer importProgress;
    private Integer totalSamples;
    private Integer importedSamples;
    private Integer failedSamples;
    private Boolean retryable;
    private List<String> retryModes;
    private V2UserError userError;
}
