package com.tss.platform.dto.v2;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class V2DatasetUploadDto {
    private String uploadId;
    private String status;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long uploadedBytes;
    private List<Integer> uploadedPartIndexes;
    private String importJobId;
    private String datasetId;
    private String workspaceId;
    private Long workspaceRevision;
    private String targetKind;
    private String targetOperation;
    private String targetResourceId;
    private String versionLabel;
    private Boolean strictManifest;
    private String artifactSpecId;
    private String displayStatus;
    private Integer importProgress;
    private V2UserError userError;
    private Instant createdAt;
    private Instant updatedAt;
}
