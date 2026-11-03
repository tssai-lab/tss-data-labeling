package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class UpdateServerEnabledRequest {
    /** true=启用（参与调度），false=禁用（不再分配新任务） */
    private Boolean enabled;
}
