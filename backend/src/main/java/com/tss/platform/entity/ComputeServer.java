package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "compute_server")
public class ComputeServer {

    @Id
    @Column(name = "server_ip", length = 45)
    private String serverIp;

    @Column(name = "hostname", nullable = false, length = 128)
    private String hostname;

    @Column(name = "status", length = 16)
    private String status = "online";

    @Column(name = "spec_cpu", length = 64)
    private String specCpu;

    @Column(name = "spec_memory", length = 64)
    private String specMemory;

    @Column(name = "spec_gpu", length = 128)
    private String specGpu;

    @Column(name = "spec_os", length = 64)
    private String specOs;

    @Column(name = "cpu_cores")
    private Double cpuCores;

    @Column(name = "memory_gib")
    private Double memoryGib;

    @Column(name = "gpu_count")
    private Integer gpuCount = 0;

    @Column(name = "k8s_node_name", length = 256)
    private String k8sNodeName;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "k8s_labels_json", columnDefinition = "TEXT")
    private String k8sLabelsJson;

    @Column(name = "deleted")
    private Boolean deleted = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
