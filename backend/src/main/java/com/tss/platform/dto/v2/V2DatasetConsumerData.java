package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.Map;

@Data
public class V2DatasetConsumerData {
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
    private String previewUrl;
    private String downloadUrl;
}
