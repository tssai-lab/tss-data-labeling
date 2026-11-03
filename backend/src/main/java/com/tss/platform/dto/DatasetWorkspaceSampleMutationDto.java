package com.tss.platform.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DatasetWorkspaceSampleMutationDto {
    private String sampleId;
    private String datasetVersionId;
    private Boolean deleted;
    private Instant deletedAt;
}
