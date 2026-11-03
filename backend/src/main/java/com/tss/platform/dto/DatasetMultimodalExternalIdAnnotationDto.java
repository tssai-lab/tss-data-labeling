package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetMultimodalExternalIdAnnotationDto {
    private String annotationId;
    private String sampleDataId;
    private String annotationType;
    private String format;
    private String fileName;
    private Long sizeBytes;
    private String checksum;
    private String contentType;
    private String downloadUrl;
}
