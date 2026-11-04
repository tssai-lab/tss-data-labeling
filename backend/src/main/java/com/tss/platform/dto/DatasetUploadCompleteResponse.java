package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DatasetUploadCompleteResponse {
    private String uploadId;
    private String id;
    private String assetId;
    private String name;
    private String version;
    private Integer versionNo;
    private String versionLabel;
    private String description;
    private String changeLog;
    private String parentVersionId;
    private String type;
    private String cvTaskType;
    private String annotationFormat;
    private String remark;
    private String fileName;
    private Long sizeBytes;
    private String status;
    private String uploadStatus;
    private String datasetVersionId;
    private String versionStatus;
    private String importJobId;
    private Boolean strictManifest;
    private String importStatus;
    private Integer ownerUserId;
    private Instant createdAt;
    private Instant updatedAt;
}
