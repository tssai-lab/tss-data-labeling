package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class MetricPoint {
    private int tickIndex;
    private String fullTime;    // "YYYY-MM-DD HH:mm:ss"
    private String time;        // display label
    private String type;        // "CPU" / "内存" / "GPU"
    private Double value;
}
