package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class DatasetUploadProgressDto {
    private String uploadId;
    private String status;
    private String uploadStatus;
    private String versionStatus;
    private String importJobId;
    private String importStatus;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long uploadedBytes;
    private List<Integer> uploadedPartIndexes;
    private String assetId;
    private String versionId;
    private String artifactSpecId;
    private Integer versionNo;
    private String versionLabel;
    private String description;
    private String changeLog;
    private String parentVersionId;
    private String cvTaskType;
    private String annotationFormat;
    private Boolean strictManifest;
    private Instant createdAt;
    private Instant updatedAt;
}
