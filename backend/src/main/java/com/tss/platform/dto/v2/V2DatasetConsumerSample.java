package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class V2DatasetConsumerSample {
    private String sampleId;
    private String externalId;
    private Integer sampleIndex;
    private Map<String, Object> tags;
    private Map<String, Object> metadata;
    private List<V2DatasetConsumerData> data;
    private List<V2DatasetConsumerAnnotation> annotations;
}
