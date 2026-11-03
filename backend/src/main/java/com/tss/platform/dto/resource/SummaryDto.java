package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class SummaryDto {
    private int total;
    private int online;
    private int runningTasks;
    private int queuedTasks;
    private String avgGpu;   // 如 "62.3"；无可用 GPU 指标时为 null
}
