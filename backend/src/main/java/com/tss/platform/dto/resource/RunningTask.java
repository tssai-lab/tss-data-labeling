package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class RunningTask {
    private String id;
    private String name;
    private String model;      // model_version_id
    private String dataset;    // dataset_version_id
    private String startTime;  // "YYYY-MM-DD HH:mm:ss"
    private int progress;
    private double cpuUsage;
    private double memUsage;   // GB
    private double gpuUsage;
}
