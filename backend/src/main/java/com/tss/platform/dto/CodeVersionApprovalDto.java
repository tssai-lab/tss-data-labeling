package com.tss.platform.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodeVersionApprovalDto {
    private String codeVersionId;
    private String approvalStatus;
}
