package com.tss.platform.dto;

import lombok.Data;

@Data
public class ModelCodePreviewDto {
    private String path;
    private String fileName;
    private String content;
    private Long sizeBytes;
}
