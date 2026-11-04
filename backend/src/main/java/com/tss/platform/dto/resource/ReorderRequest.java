package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class ReorderRequest {
    private String taskId;
    private String direction;   // "up" / "down"
}
