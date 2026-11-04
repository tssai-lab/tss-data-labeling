package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class PriorityRequest {
    private String taskId;
    private String priority;   // "高" / "中" / "低"
}
