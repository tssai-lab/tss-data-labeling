package com.tss.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodeUploadResultDto {
    private String codeAssetId;
    private String codeVersionId;
    private String version;
    private String fileName;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String storagePath;
    private Long sizeBytes;
    private String trainingProfile;
    private String status;
    private String approvalStatus;
}
