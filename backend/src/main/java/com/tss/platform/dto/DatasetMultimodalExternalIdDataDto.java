package com.tss.platform.dto;

import lombok.Data;

@Data
public class DatasetMultimodalExternalIdDataDto {
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
    private String previewUrl;
    private String downloadUrl;
}
