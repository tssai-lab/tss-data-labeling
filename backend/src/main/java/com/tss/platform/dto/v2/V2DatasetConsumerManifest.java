package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;

@Data
public class V2DatasetConsumerManifest {
    private String datasetVersionId;
    private String datasetId;
    private String type;
    private String versionLabel;
    private String status;
    private Integer page;
    private Integer pageSize;
    private Long totalSamples;
    private List<V2DatasetConsumerSample> samples;
}
