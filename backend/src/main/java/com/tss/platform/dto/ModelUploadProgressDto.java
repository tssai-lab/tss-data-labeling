package com.tss.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class ModelUploadProgressDto {
    private String uploadId;
    private String status;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long uploadedBytes;
    private List<Integer> uploadedPartIndexes;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String storagePath;
    private String assetId;
    private String versionId;
    private String artifactSpecId;
    private String commitInfo;
    private Map<String, Object> hyperParams;
    private Instant createdAt;
    private Instant updatedAt;
}
