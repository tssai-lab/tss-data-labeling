package com.tss.platform.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class ModelVersionCreateRequest {

    private String assetId;
    private String version;
    private String description;
    private String changeLog;
    private String commitInfo;
    private Map<String, Object> hyperParams;

    // Server-managed fields are declared so that attempts to set them can be rejected explicitly.
    private String id;
    private String status;
    private String fileName;
    private String storagePath;
    private Long sizeBytes;
    private String artifactSha256;
    private String artifactSpecId;
    private Instant publishedAt;
    private Instant createdAt;
    private Integer createdBy;
    private Integer ownerUserId;
    private Boolean deleted;
    private Instant deletedAt;
}
