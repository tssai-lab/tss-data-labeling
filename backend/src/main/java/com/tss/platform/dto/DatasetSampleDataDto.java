package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class DatasetSampleDataDto {
    private String sampleDataId;
    private String dataType;
    private String sensor;
    private String channel;
    private Integer seq;
    private String format;
    private String fileName;
    private Long sizeBytes;
    private String checksum;
    private String contentType;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
