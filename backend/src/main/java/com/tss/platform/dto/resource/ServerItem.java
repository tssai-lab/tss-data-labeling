package com.tss.platform.dto.resource;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class ServerItem {
    private String serverIp;
    private String hostname;
    private Boolean enabled = true;   // 是否参与调度（false=禁用，不分配新任务）
    private String status;         // online / warning
    private Double cpuRate;
    private Double memRate;
    private Double gpuRate;
    private Double diskRate;
    private Double gpuMemRate;
    private Double networkIn;
    private Double networkOut;
    private Double gpuTemp;
    private List<GpuDeviceItem> gpuDevices;
    private String metricsStatus;  // fresh / temporarily_unavailable / stale / unavailable
    private Instant metricsLastSuccessAt;
    private Instant metricsLastAttemptAt;
    private String metricsMessage;
    private Boolean nodeReady;
    private Boolean nodeUnschedulable;
    private Boolean nodeMemoryPressure;
    private Boolean nodeDiskPressure;
    private Boolean nodePidPressure;
    private String nodeHealthStatus; // healthy / warning / unavailable
    private int runTask;
    private int waitTask;
    private List<RunningTask> runningTasks;
    private List<QueuedTask> queuedTasks;
    private ServerSpecs specs;
}
