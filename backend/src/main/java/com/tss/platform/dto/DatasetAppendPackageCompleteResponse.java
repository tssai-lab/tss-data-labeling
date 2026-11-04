package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetAppendPackageCompleteResponse {
    private String uploadId;
    private String draftVersionId;
    private String datasetVersionId;
    private String packageId;
    private String packageRole;
    private Integer packageOrder;
    private String importJobId;
    private Boolean strictManifest;
    private String uploadStatus;
    private String versionStatus;
    private String importStatus;
}
