package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DatasetCvFolderUploadResponse {
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
    private Integer ownerUserId;
    private Instant createdAt;
    private Instant updatedAt;
}
