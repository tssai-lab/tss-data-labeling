package com.tss.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
public class InferenceScriptVersionDto {
    private String id;
    private String assetId;
    private String scriptName;
    private String version;
    private String fileName;
    private String storagePath;
    private Long sizeBytes;
    private String runtime;
    private String entryFile;
    private JsonNode paramsSchema;
    private String status;
    private Integer ownerUserId;
    /** 演示资产：全局共享、只读 */
    private Boolean isDemo;
    private Instant createdAt;
}
