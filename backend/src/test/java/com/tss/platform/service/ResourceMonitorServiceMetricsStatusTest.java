package com.tss.platform.service;

import com.tss.platform.config.ComputeProperties;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto.KubernetesNodeHealth;
import com.tss.platform.dto.resource.ServerItem;
import com.tss.platform.dto.resource.MetricsResponse;
import com.tss.platform.inference.InferenceExecutorRouter;
import com.tss.platform.training.TrainingExecutorRouter;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.ServerMetricSnapshot;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.ServerMetricHistoryRepository;
import com.tss.platform.repository.ServerMetricSnapshotRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ResourceMonitorServiceMetricsStatusTest {

    @Test
    void distinguishesRealZeroFailedSampleStaleSampleAndMissingSample() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        List<ComputeServer> servers = List.of(
                server("fresh-zero"),
                server("failed"),
                server("stale"),
                server("missing")
        );
        when(fixture.serverRepo.findByDeletedFalse()).thenReturn(servers);
        when(fixture.snapshotRepo.findAll()).thenReturn(List.of(
                snapshot("fresh-zero", 0, 0, now.minusSeconds(10), now.minusSeconds(10), "online"),
                snapshot("failed", 42, 51, now.minusSeconds(10), now, "warning"),
                snapshot("stale", 7, 8, now.minusSeconds(180), now.minusSeconds(180), "online")
        ));
        when(fixture.diagnostics.collectNodeHealth()).thenReturn(healthyNodes(servers));

        Map<String, ServerItem> items = index(fixture.service.listServers(null, "all"));

        assertThat(items.get("fresh-zero").getCpuRate()).isZero();
        assertThat(items.get("fresh-zero").getMetricsStatus()).isEqualTo("fresh");
        assertThat(items.get("fresh-zero").getStatus()).isEqualTo("online");

        assertThat(items.get("failed").getCpuRate()).isEqualTo(42);
        assertThat(items.get("failed").getMetricsStatus()).isEqualTo("temporarily_unavailable");
        assertThat(items.get("failed").getStatus()).isEqualTo("warning");

        assertThat(items.get("stale").getMetricsStatus()).isEqualTo("stale");
        assertThat(items.get("stale").getStatus()).isEqualTo("warning");

        assertThat(items.get("missing").getMetricsStatus()).isEqualTo("unavailable");
        assertThat(items.get("missing").getStatus()).isEqualTo("warning");
        assertThat(items.get("missing").getCpuRate()).isNull();
        assertThat(items.get("missing").getGpuRate()).isNull();
    }

    @Test
    void emptyHistoryBucketsAreNullInsteadOfFakeZeros() {
        Fixture fixture = fixture();
        when(fixture.historyRepo.findByServerIpAndCollectedAtBetweenOrderByCollectedAtAsc(
                any(), any(), any(), any())).thenReturn(List.of());

        MetricsResponse response = fixture.service.getMetrics("node", "1hour");

        assertThat(response.getPoints()).hasSize(72);
        assertThat(response.getPoints()).allSatisfy(point -> assertThat(point.getValue()).isNull());
    }

    @Test
    void nodePressureOverridesFreshMetrics() {
        Fixture fixture = fixture();
        ComputeServer server = server("disk-pressure");
        when(fixture.serverRepo.findByDeletedFalse()).thenReturn(List.of(server));
        Instant sampledAt = Instant.now().minusSeconds(5);
        when(fixture.snapshotRepo.findAll()).thenReturn(List.of(
                snapshot("disk-pressure", 1, 2, sampledAt, sampledAt, "online")
        ));
        KubernetesNodeHealth node = healthyNode("disk-pressure");
        node.setDiskPressure(true);
        node.setHealthStatus("warning");
        when(fixture.diagnostics.collectNodeHealth()).thenReturn(
                new KubernetesResourceDiagnosticsService.NodeHealthCollection(
                        true, null, Map.of("disk-pressure", node))
        );

        ServerItem item = fixture.service.listServers(null, "all").get(0);

        assertThat(item.getMetricsStatus()).isEqualTo("fresh");
        assertThat(item.getNodeDiskPressure()).isTrue();
        assertThat(item.getNodeHealthStatus()).isEqualTo("warning");
        assertThat(item.getStatus()).isEqualTo("warning");
    }

    private static Fixture fixture() {
        ComputeServerRepository serverRepo = mock(ComputeServerRepository.class);
        ServerMetricSnapshotRepository snapshotRepo = mock(ServerMetricSnapshotRepository.class);
        ServerMetricHistoryRepository historyRepo = mock(ServerMetricHistoryRepository.class);
        TrainingExperimentVersionRepository trainingRepo = mock(TrainingExperimentVersionRepository.class);
        InferenceTaskRepository inferenceRepo = mock(InferenceTaskRepository.class);
        JobScheduler scheduler = mock(JobScheduler.class);
        KubernetesResourceDiagnosticsService diagnostics = mock(KubernetesResourceDiagnosticsService.class);
        ComputeProperties properties = new ComputeProperties();
        properties.setCollectIntervalMs(30_000);
        TrainingExecutorRouter trainingExecutorRouter = mock(TrainingExecutorRouter.class);
        InferenceExecutorRouter inferenceExecutorRouter = mock(InferenceExecutorRouter.class);
        ResourceMonitorService service = new ResourceMonitorService(
                serverRepo,
                snapshotRepo,
                historyRepo,
                trainingRepo,
                inferenceRepo,
                scheduler,
                diagnostics,
                properties,
                trainingExecutorRouter,
                inferenceExecutorRouter
        );
        return new Fixture(service, serverRepo, snapshotRepo, historyRepo, diagnostics);
    }

    private static KubernetesResourceDiagnosticsService.NodeHealthCollection healthyNodes(
            List<ComputeServer> servers
    ) {
        Map<String, KubernetesNodeHealth> nodes = new LinkedHashMap<>();
        for (ComputeServer server : servers) {
            nodes.put(server.getServerIp(), healthyNode(server.getServerIp()));
        }
        return new KubernetesResourceDiagnosticsService.NodeHealthCollection(true, null, nodes);
    }

    private static KubernetesNodeHealth healthyNode(String name) {
        KubernetesNodeHealth node = new KubernetesNodeHealth();
        node.setName(name);
        node.setReady(true);
        node.setUnschedulable(false);
        node.setMemoryPressure(false);
        node.setDiskPressure(false);
        node.setPidPressure(false);
        node.setHealthStatus("healthy");
        return node;
    }

    private static ComputeServer server(String name) {
        ComputeServer server = new ComputeServer();
        server.setServerIp(name);
        server.setK8sNodeName(name);
        server.setHostname(name);
        return server;
    }

    private static ServerMetricSnapshot snapshot(
            String serverIp,
            double cpu,
            double memory,
            Instant lastSuccess,
            Instant lastAttempt,
            String status
    ) {
        ServerMetricSnapshot snapshot = new ServerMetricSnapshot();
        snapshot.setServerIp(serverIp);
        snapshot.setCpuRate(cpu);
        snapshot.setMemRate(memory);
        snapshot.setStatus(status);
        snapshot.setLastHeartbeat(lastSuccess);
        snapshot.setUpdatedAt(lastAttempt);
        return snapshot;
    }

    private static Map<String, ServerItem> index(List<ServerItem> items) {
        Map<String, ServerItem> result = new LinkedHashMap<>();
        for (ServerItem item : items) {
            result.put(item.getServerIp(), item);
        }
        return result;
    }

    private record Fixture(
            ResourceMonitorService service,
            ComputeServerRepository serverRepo,
            ServerMetricSnapshotRepository snapshotRepo,
            ServerMetricHistoryRepository historyRepo,
            KubernetesResourceDiagnosticsService diagnostics
    ) {
    }
}
