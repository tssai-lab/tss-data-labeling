package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetPreviewFileDto {
    private String path;
    private String fileName;
    private String extension;
    private String kind;
    private Long sizeBytes;
    private Boolean previewAllowed;
    private String previewUrl;
    private String message;
}
