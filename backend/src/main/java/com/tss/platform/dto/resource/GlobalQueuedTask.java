package com.tss.platform.dto.resource;

import lombok.Data;

/**
 * 全局排队任务（跨服务器视角）。
 * 只包含尚未分配节点（serverIp 为 null）的 queued / pending 训练任务，
 * 它们按所需资源池（nodePool）分组，组内排序决定谁先获得该池的空闲资源。
 */
@Data
public class GlobalQueuedTask {
    private String id;
    private String name;
    private String model;
    private String dataset;
    private String submitTime;     // "YYYY-MM-DD HH:mm:ss"
    private String priority;       // 高 / 中 / 低
    private int queueSortIndex;
    private String status;         // queued / pending
    /** 分组键：tss.ai/node-pool 标签值（cpu/gpu/h100…），无则用完整 selector 拼接，再兜底 custom */
    private String nodePool;
    /** 组内展示序号（1-based），仅在同一资源池内有意义 */
    private int positionInPool;
}
