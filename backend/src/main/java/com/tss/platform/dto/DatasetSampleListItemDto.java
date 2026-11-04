package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class DatasetSampleListItemDto {
    private String sampleId;
    private String datasetVersionId;
    private String externalId;
    private Integer sampleIndex;
    private Map<String, Object> tags;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
