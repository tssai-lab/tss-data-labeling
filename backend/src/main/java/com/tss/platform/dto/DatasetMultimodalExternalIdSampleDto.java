package com.tss.platform.dto;

import lombok.Data;

import java.util.List;

@Data
public class DatasetMultimodalExternalIdSampleDto {
    private String datasetVersionId;
    private String sampleId;
    private String externalId;
    private Integer sampleIndex;
    private List<DatasetMultimodalExternalIdDataDto> data;
    private List<DatasetMultimodalExternalIdAnnotationDto> annotations;
}
