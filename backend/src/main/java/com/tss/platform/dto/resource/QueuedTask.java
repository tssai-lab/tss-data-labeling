package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class QueuedTask {
    private String id;
    private String name;
    private String model;
    private String dataset;
    private String submitTime;     // "YYYY-MM-DD HH:mm:ss"
    private String priority;       // 高 / 中 / 低
    private int queueSortIndex;
}
