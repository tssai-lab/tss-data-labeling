package com.tss.platform.module1.dto;

import lombok.Data;

@Data
public class UserApiPolicyUpdateRequest {
    private Boolean enabled;
    private Integer maxConcurrentRequests;
    private Long version;
}
