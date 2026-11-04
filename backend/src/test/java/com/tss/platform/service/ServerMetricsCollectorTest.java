package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.ComputeProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.ServerMetricSnapshot;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.ServerMetricHistoryRepository;
import com.tss.platform.repository.ServerMetricSnapshotRepository;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMetricsCollectorTest {

    @Test
    void discoversKubernetesNodesWhenMetricsServerIsUnavailable() {
        Map<String, double[]> capacities = new LinkedHashMap<>();
        capacities.put("tss-ai-control-01", new double[]{16, 64, 0, 100});
        capacities.put("tss-ai-worker-01", new double[]{32, 128, 2, 500});

        assertThat(ServerMetricsCollector.discoveredNodeNames(
                capacities,
                Map.of(),
                Map.of(),
                Map.of()
        )).containsExactly("tss-ai-control-01", "tss-ai-worker-01");
    }

    @Test
    void mergesPartialNodeDiscoveryWithoutDuplicatesOrBlankNames() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tss-ai-worker-01", "{}");
        labels.put("", "{}");

        assertThat(ServerMetricsCollector.discoveredNodeNames(
                Map.of("tss-ai-control-01", new double[]{16, 64}),
                Map.of("tss-ai-worker-01", "Ubuntu"),
                labels,
                Map.of("tss-ai-control-01", new ServerMetricsCollector.TopData(
                        0.1, 1024, 0, 0, 0))
        )).containsExactly("tss-ai-control-01", "tss-ai-worker-01");
    }

    @Test
    void resolvesNodeInternalIpWithoutDependingOnHostnameDns() {
        Fixture fixture = fixture();
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success("""
                        {"items":[{"metadata":{"name":"tss-ai-worker-01"},"status":{"addresses":[
                          {"type":"Hostname","address":"tss-ai-worker-01"},
                          {"type":"InternalIP","address":"10.201.96.67"}
                        ]}}]}
                        """));

        assertThat(fixture.collector.fetchNodeInternalIps())
                .containsEntry("tss-ai-worker-01", "10.201.96.67");
    }

    @Test
    void derivesPlatformSchedulabilityFromExplicitPolicyCordonAndBlockingTaints() throws Exception {
        Fixture fixture = fixture();
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success("""
                        {"items":[
                          {"metadata":{"name":"control","labels":{"node-role.kubernetes.io/control-plane":""}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"}]}},
                          {"metadata":{"name":"control-cpu","labels":{"node-role.kubernetes.io/control-plane":"","tss.ai/node-pool":"cpu","tss.ai/accelerator":"nvidia","tss.ai/gpu-schedulable":"false","tss.ai/platform-schedulable":"true","tss.ai/platform-max-active-tasks":"1"}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"}]}},
                          {"metadata":{"name":"control-with-gpu","labels":{"node-role.kubernetes.io/control-plane":"","tss.ai/node-pool":"cpu","tss.ai/accelerator":"nvidia","tss.ai/platform-schedulable":"true","tss.ai/platform-max-active-tasks":"1"}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"}]}},
                          {"metadata":{"name":"control-without-cap","labels":{"node-role.kubernetes.io/control-plane":"","tss.ai/node-pool":"cpu","tss.ai/platform-schedulable":"true"}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"}]}},
                          {"metadata":{"name":"control-maintenance","labels":{"node-role.kubernetes.io/control-plane":"","tss.ai/platform-schedulable":"true"}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"},{"key":"maintenance","effect":"NoSchedule"}]}},
                          {"metadata":{"name":"control-evicted","labels":{"node-role.kubernetes.io/control-plane":"","tss.ai/platform-schedulable":"true"}},
                           "spec":{"taints":[{"key":"node-role.kubernetes.io/control-plane","effect":"NoSchedule"},{"key":"offline","effect":"NoExecute"}]}},
                          {"metadata":{"name":"cordoned","labels":{}},"spec":{"unschedulable":true}},
                          {"metadata":{"name":"preferred","labels":{}},
                           "spec":{"taints":[{"key":"shared","effect":"PreferNoSchedule"}]}},
                          {"metadata":{"name":"worker-disabled","labels":{"tss.ai/node-pool":"cpu","tss.ai/platform-schedulable":"false"}},"spec":{}},
                          {"metadata":{"name":"worker","labels":{"tss.ai/node-pool":"cpu"}},"spec":{}}
                        ]}
                        """));

        Map<String, String> labels = fixture.collector.fetchNodeLabelsJson();

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(labels.get("control"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("control-cpu"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("true");
        assertThat(mapper.readTree(labels.get("control-with-gpu"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("control-without-cap"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("control-maintenance"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("control-evicted"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("cordoned"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("preferred"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("true");
        assertThat(mapper.readTree(labels.get("worker-disabled"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("false");
        assertThat(mapper.readTree(labels.get("worker"))
                .path(JobScheduler.PLATFORM_SCHEDULABLE_LABEL).asText()).isEqualTo("true");
    }

    @Test
    void oneInvalidNodeMetricDoesNotDiscardHealthyNodes() {
        Fixture fixture = fixture();
        String metrics = """
                {"items":[
                  {"metadata":{"name":"bad-node"},"usage":{"cpu":"7x","memory":"1Gi"}},
                  {"metadata":{"name":"good-node"},"usage":{"cpu":"128724u","memory":"2Gi"}}
                ]}
                """;
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success(metrics));

        Map<String, ServerMetricsCollector.TopData> result = fixture.collector.collectTop();

        assertThat(result).containsOnlyKeys("good-node");
        assertThat(result.get("good-node").cpuUsed()).isEqualTo(0.128724);
        assertThat(result.get("good-node").memBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }

    @Test
    void unavailableMetricsPreserveLastSuccessAndDoNotCreateFakeHistory() {
        Fixture fixture = fixture();
        ComputeServer server = server("k8s-master");
        ServerMetricSnapshot snapshot = new ServerMetricSnapshot();
        snapshot.setServerIp(server.getServerIp());
        snapshot.setCpuRate(42.0);
        snapshot.setMemRate(51.0);
        snapshot.setStatus("online");
        Instant lastSuccess = Instant.parse("2026-08-12T10:00:00Z");
        snapshot.setLastHeartbeat(lastSuccess);
        snapshot.setUpdatedAt(lastSuccess);
        when(fixture.snapshotRepo.findByServerIp(server.getServerIp())).thenReturn(Optional.of(snapshot));
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "", "metrics unavailable"));

        fixture.collector.collectOne(server, null, new double[]{4, 16});

        assertThat(snapshot.getCpuRate()).isEqualTo(42.0);
        assertThat(snapshot.getMemRate()).isEqualTo(51.0);
        assertThat(snapshot.getLastHeartbeat()).isEqualTo(lastSuccess);
        assertThat(snapshot.getUpdatedAt()).isAfter(lastSuccess);
        assertThat(snapshot.getStatus()).isEqualTo("warning");
        verify(fixture.snapshotRepo).save(snapshot);
        verify(fixture.historyRepo, never()).save(any());
    }

    @Test
    void unavailableFirstSampleDoesNotPersistHealthyZeros() {
        Fixture fixture = fixture();
        ComputeServer server = server("new-node");
        when(fixture.snapshotRepo.findByServerIp(server.getServerIp())).thenReturn(Optional.empty());

        fixture.collector.collectOne(server, null, new double[]{4, 16});

        verify(fixture.snapshotRepo, never()).save(any());
        verify(fixture.historyRepo, never()).save(any());
    }

    @Test
    void networkRateSkipsLegacyMegabyteSampleThenUsesByteDelta() {
        assertThat(ServerMetricsCollector.networkRate(10_485_760, 10L, 30)).isNull();
        assertThat(ServerMetricsCollector.networkRate(12_582_912, 10_485_760L, 2)).isEqualTo(1.0);
        assertThat(ServerMetricsCollector.networkRate(10_000, 10_000L, 30)).isZero();
        assertThat(ServerMetricsCollector.networkRate(10_000, null, 30)).isNull();
        assertThat(ServerMetricsCollector.networkRate(9_000, 10_000L, 30)).isNull();
    }

    @Test
    void successfulZeroDiskAndFirstNetworkSampleDistinguishZeroFromUnknown() {
        Fixture fixture = fixture();
        ComputeServer server = server("zero-node");
        ServerMetricSnapshot snapshot = new ServerMetricSnapshot();
        snapshot.setServerIp(server.getServerIp());
        snapshot.setDiskRate(50.0);
        snapshot.setNetworkIn(9.0);
        snapshot.setNetworkOut(8.0);
        when(fixture.snapshotRepo.findByServerIp(server.getServerIp())).thenReturn(Optional.of(snapshot));
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success("""
                        {"node":{"fs":{"capacityBytes":1000,"availableBytes":1000}},
                         "network":{"interfaces":[{"rxBytes":0,"txBytes":0}]}}
                        """));

        fixture.collector.collectOne(
                server,
                new ServerMetricsCollector.TopData(0.1, 1024, 1.0, 2.0, 0),
                new double[]{4, 16}
        );

        assertThat(snapshot.getDiskRate()).isZero();
        assertThat(snapshot.getNetworkIn()).isNull();
        assertThat(snapshot.getNetworkOut()).isNull();
    }

    @Test
    void failedDiskSampleBecomesUnknownInsteadOfKeepingOldWarning() {
        Fixture fixture = fixture();
        ComputeServer server = server("disk-node");
        ServerMetricSnapshot snapshot = new ServerMetricSnapshot();
        snapshot.setServerIp(server.getServerIp());
        snapshot.setDiskRate(85.0);
        when(fixture.snapshotRepo.findByServerIp(server.getServerIp())).thenReturn(Optional.of(snapshot));
        when(fixture.shellRunner.run(any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "", "stats unavailable"));

        fixture.collector.collectOne(
                server,
                new ServerMetricsCollector.TopData(0.1, 1024, 1.0, 2.0, 0),
                new double[]{4, 16}
        );

        assertThat(snapshot.getDiskRate()).isNull();
        assertThat(snapshot.getStatus()).isEqualTo("online");
    }

    @Test
    void parsesIndependentDcgmUtilizationMemoryAndTemperatureMetrics() {
        ServerMetricsCollector.GpuMetrics metrics = ServerMetricsCollector.parseGpuMetrics("""
                # HELP ignored
                DCGM_FI_DEV_GPU_UTIL{gpu="0",UUID="GPU-aaaaaaaa",modelName="RTX 4080"} 0
                DCGM_FI_DEV_GPU_UTIL{gpu="1",UUID="GPU-bbbbbbbb",modelName="RTX 4080"} 20
                DCGM_FI_DEV_FB_USED{gpu="0",UUID="GPU-aaaaaaaa",modelName="RTX 4080"} 100
                DCGM_FI_DEV_FB_USED{gpu="1",UUID="GPU-bbbbbbbb",modelName="RTX 4080"} 300
                DCGM_FI_DEV_FB_TOTAL{gpu="0",UUID="GPU-aaaaaaaa",modelName="RTX 4080"} 1000
                DCGM_FI_DEV_FB_TOTAL{gpu="1",UUID="GPU-bbbbbbbb",modelName="RTX 4080"} 1000
                DCGM_FI_DEV_GPU_TEMP{gpu="0",UUID="GPU-aaaaaaaa",modelName="RTX 4080"} 40
                DCGM_FI_DEV_GPU_TEMP{gpu="1",UUID="GPU-bbbbbbbb",modelName="RTX 4080"} 50
                """);

        assertThat(metrics.utilizationRate()).isEqualTo(10.0);
        assertThat(metrics.memoryRate()).isEqualTo(20.0);
        assertThat(metrics.temperature()).isEqualTo(45.0);
        assertThat(metrics.available()).isTrue();
        assertThat(metrics.devices()).hasSize(2);
        assertThat(metrics.devices().get(0).modelName()).isEqualTo("RTX 4080");
        assertThat(metrics.devices().get(0).totalMemoryMiB()).isEqualTo(1000L);
        assertThat(metrics.devices().get(0).freeMemoryMiB()).isEqualTo(900L);
        assertThat(metrics.devices().get(0).hostGpuIndex()).isEqualTo("0");
        assertThat(metrics.devices().get(0).uuid()).isEqualTo("GPU-aaaaaaaa");
        assertThat(metrics.devices().get(0).utilizationRate()).isEqualTo(0.0);
        assertThat(metrics.devices().get(0).temperatureCelsius()).isEqualTo(40.0);
    }

    @Test
    void calculatesDcgmMemoryRateFromDefaultUsedAndFreeMetrics() {
        ServerMetricsCollector.GpuMetrics metrics = ServerMetricsCollector.parseGpuMetrics("""
                DCGM_FI_DEV_FB_USED{gpu="0"} 100
                DCGM_FI_DEV_FB_USED{gpu="1"} 300
                DCGM_FI_DEV_FB_FREE{gpu="0"} 900
                DCGM_FI_DEV_FB_FREE{gpu="1"} 700
                """);

        assertThat(metrics.memoryRate()).isEqualTo(20.0);
    }

    @Test
    void incompleteDcgmMemoryPairsRemainUnavailable() {
        ServerMetricsCollector.GpuMetrics metrics = ServerMetricsCollector.parseGpuMetrics("""
                DCGM_FI_DEV_FB_USED{gpu="0"} 100
                DCGM_FI_DEV_FB_USED{gpu="1"} 300
                DCGM_FI_DEV_FB_FREE{gpu="0"} 900
                """);

        assertThat(metrics.memoryRate()).isNull();
    }

    @Test
    void malformedOrMissingDcgmMetricsRemainUnavailable() {
        ServerMetricsCollector.GpuMetrics metrics = ServerMetricsCollector.parseGpuMetrics("""
                DCGM_FI_DEV_GPU_UTIL{gpu="0"} NaN
                unrelated_metric 0
                """);

        assertThat(metrics.utilizationRate()).isNull();
        assertThat(metrics.memoryRate()).isNull();
        assertThat(metrics.temperature()).isNull();
        assertThat(metrics.available()).isFalse();
    }

    private static ComputeServer server(String name) {
        ComputeServer server = new ComputeServer();
        server.setServerIp(name);
        server.setK8sNodeName(name);
        return server;
    }

    private static Fixture fixture() {
        ComputeServerRepository serverRepo = mock(ComputeServerRepository.class);
        ServerMetricSnapshotRepository snapshotRepo = mock(ServerMetricSnapshotRepository.class);
        ServerMetricHistoryRepository historyRepo = mock(ServerMetricHistoryRepository.class);
        TrainingEnvironmentService envService = mock(TrainingEnvironmentService.class);
        ShellCommandRunner shellRunner = mock(ShellCommandRunner.class);
        Path root = Path.of(".").toAbsolutePath().normalize();
        when(envService.resolveProjectRoot()).thenReturn(root);
        when(envService.resolveKubeconfig()).thenReturn(root.resolve("kubeconfig"));
        when(envService.resolveKubectl()).thenReturn(root.resolve("kubectl"));
        when(envService.kubectlCommand(any(), any(String[].class)))
                .thenAnswer(invocation -> List.of("kubectl"));
        ServerMetricsCollector collector = new ServerMetricsCollector(
                serverRepo,
                snapshotRepo,
                historyRepo,
                envService,
                shellRunner,
                new ComputeProperties(),
                new ObjectMapper()
        );
        return new Fixture(collector, snapshotRepo, historyRepo, shellRunner);
    }

    private record Fixture(
            ServerMetricsCollector collector,
            ServerMetricSnapshotRepository snapshotRepo,
            ServerMetricHistoryRepository historyRepo,
            ShellCommandRunner shellRunner
    ) {
    }
}
