package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class DatasetSampleDetailDto {
    private String sampleId;
    private String datasetVersionId;
    private String externalId;
    private Integer sampleIndex;
    private Map<String, Object> tags;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private List<DatasetSampleDataDto> data;
    private List<DatasetAnnotationDto> annotations;
}
