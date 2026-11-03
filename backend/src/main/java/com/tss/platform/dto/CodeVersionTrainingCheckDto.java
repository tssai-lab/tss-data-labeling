package com.tss.platform.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class CodeVersionTrainingCheckDto {
    private String codeVersionId;
    private String trainingProfile;
    private String trainingProfileDisplayName;
    private Boolean passed;
    private Boolean reused;
    private String approvalStatus;
    private List<String> reasons;
    private Instant checkedAt;
}
