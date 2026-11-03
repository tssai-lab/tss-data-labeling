package com.tss.platform.dto.resource;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class MetricsResponse {
    private String interval;    // "1min" / "10min" / "1hour" / "1day"
    private String spanLabel;   // "近 1 小时（按分钟）" etc.
    private List<MetricPoint> points;
    private String metricsStatus;
    private Instant metricsLastSuccessAt;
    private Instant metricsLastAttemptAt;
    private String metricsMessage;
}
