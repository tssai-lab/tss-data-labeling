package com.tss.platform.dto;

import lombok.Data;

@Data
public class TrainingModelArtifactDto {
    private String fileName;
    private String objectName;
    private String modelFileName;
    private String format;
    private String sha256;
    private Long sizeBytes;
}
