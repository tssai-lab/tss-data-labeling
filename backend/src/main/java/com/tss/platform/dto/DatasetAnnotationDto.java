package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class DatasetAnnotationDto {
    private String annotationId;
    private String sampleDataId;
    private String annotationType;
    private String format;
    private String fileName;
    private Long sizeBytes;
    private String checksum;
    private String contentType;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
