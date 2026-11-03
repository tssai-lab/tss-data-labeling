package com.tss.platform.dto;

import lombok.Data;

@Data
public class PointCloudPreviewFileDto {
    private String path;
    private String fileName;
    private String format;
    private Long sizeBytes;
    private String previewUrl;
    private boolean previewAllowed;
    private String message;
}
