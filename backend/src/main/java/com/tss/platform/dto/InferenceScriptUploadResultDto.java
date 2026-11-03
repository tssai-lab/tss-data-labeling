package com.tss.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InferenceScriptUploadResultDto {
    private String scriptAssetId;
    private String scriptVersionId;
    private String scriptName;
    private String version;
    private String fileName;
    private String storagePath;
    private Long sizeBytes;
    private String runtime;
    private String entryFile;
    private JsonNode paramsSchema;
    private String status;
}
