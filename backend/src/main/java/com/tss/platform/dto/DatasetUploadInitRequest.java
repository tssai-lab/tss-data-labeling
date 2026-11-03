package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetUploadInitRequest {
    private String assetId;
    private String fileName;
    private Long fileSize;
    private String fileFingerprint;
    private String datasetName;
    private String version;
    private String versionLabel;
    private String description;
    private String changeLog;
    private String parentVersionId;
    private String type;
    private String cvTaskType;
    private String annotationFormat;
    private String remark;
    private String sampleGrouping;
    private String manifestPath;
    private Boolean strictManifest;
}
