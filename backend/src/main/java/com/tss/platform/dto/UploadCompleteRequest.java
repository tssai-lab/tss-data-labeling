package com.tss.platform.dto;

import lombok.Data;

import java.util.Map;

@Data
public class UploadCompleteRequest {
    private String uploadId;
    private String assetId;
    private String modelName;
    private String version;
    private String type;
    private String remark;
    private String commitInfo;
    private Map<String, Object> hyperParams;
}
