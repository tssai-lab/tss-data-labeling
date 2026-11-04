package com.tss.platform.dto;

import lombok.Data;

import java.util.List;

@Data
public class PointCloudPreviewDto {
    private String datasetVersionId;
    private String fileName;
    private String type;
    private String format;
    private Long sizeBytes;
    private boolean previewSupported;
    private String previewUrl;
    private List<PointCloudPreviewFileDto> pointCloudFiles;
    private String message;
}
