package com.tss.platform.dto;

import lombok.Data;

import java.util.Map;

@Data
public class UploadInitRequest {
    private String fileName;
    private Long fileSize;
    private String fileFingerprint;
    private String commitInfo;
    private Map<String, Object> hyperParams;
}
