package com.tss.platform.dto;

import lombok.Data;

@Data
public class ModelCodeFileDto {
    private String path;
    private String fileName;
    private String extension;
    private Long sizeBytes;
}
