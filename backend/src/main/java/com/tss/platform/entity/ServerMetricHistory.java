package com.tss.platform.entity;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "server_metric_history")
public class ServerMetricHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_ip", nullable = false, length = 45)
    private String serverIp;

    @Column(name = "cpu_rate")
    private Double cpuRate;

    @Column(name = "mem_rate")
    private Double memRate;

    @Column(name = "gpu_rate")
    private Double gpuRate;

    @Column(name = "gpu_mem_rate")
    private Double gpuMemRate;

    @Column(name = "disk_rate")
    private Double diskRate;

    @Column(name = "network_in")
    private Double networkIn;

    @Column(name = "network_out")
    private Double networkOut;

    @Column(name = "gpu_temp")
    private Double gpuTemp;

    @Column(name = "collected_at")
    private Instant collectedAt;
}
