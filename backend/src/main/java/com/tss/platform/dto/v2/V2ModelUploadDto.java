package com.tss.platform.dto.v2;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class V2ModelUploadDto {
    private String uploadId;
    private String status;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long uploadedBytes;
    private List<Integer> uploadedPartIndexes;
    private String targetAssetId;
    private String modelId;
    private String assetId;
    private String modelName;
    private String modelVersion;
    private String taskType;
    private String remark;
    private String commitInfo;
    private Map<String, Object> hyperParams;
    private String artifactSha256;
    private String artifactSpecId;
    private Boolean isCurrent;
    private Instant createdAt;
    private Instant updatedAt;
}
