package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.Map;

@Data
public class V2DatasetConsumerAnnotation {
    private String annotationId;
    private String sampleDataId;
    private String annotationType;
    private String format;
    private String fileName;
    private Long sizeBytes;
    private String checksum;
    private String contentType;
    private Map<String, Object> metadata;
    private String downloadUrl;
}
