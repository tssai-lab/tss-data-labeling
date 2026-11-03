package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "resource-monitor.metrics")
public class ComputeProperties {

    private long collectIntervalMs = 30000;
    private int dcgmExporterPort = 9400;
    private String gpuNodeLabel = "tss.ai/node-pool=gpu";
    private int metricsRetentionDays = 7;
}
