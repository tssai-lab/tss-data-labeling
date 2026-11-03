package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.dto.resource.TrainingResourceCapabilityDto;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingResourceCapabilityServiceTest {

    @Test
    void aggregatesWithoutExposingNodeIdentityAndUsesTheSmallestGpuMemory() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer first = gpuNode("internal-control", 1);
        ComputeServer second = gpuNode("internal-worker", 2);
        when(fixture.serverRepository.findByDeletedFalse()).thenReturn(List.of(first, second));
        Instant now = Instant.now();
        fixture.store.update("internal-control", List.of(
                new GpuDeviceObservationStore.DeviceObservation("RTX 5090", 32768L, 12000L)), now);
        fixture.store.update("internal-worker", List.of(
                new GpuDeviceObservationStore.DeviceObservation("RTX 4080", 16384L, 9000L),
                new GpuDeviceObservationStore.DeviceObservation("RTX 4080", 16384L, 7000L)), now);

        TrainingResourceCapabilityDto result = fixture.service.capability("plan", "v2", "gpu-small");

        assertThat(result.eligibleNodeCount()).isEqualTo(2);
        assertThat(result.capacityAvailable()).isTrue();
        assertThat(result.gpu().models()).containsExactly("RTX 5090", "RTX 4080");
        assertThat(result.gpu().observedGpuCount()).isEqualTo(3);
        assertThat(result.gpu().safeTotalMemoryMiB()).isEqualTo(16384L);
        assertThat(result.gpu().maxFreeMemoryMiB()).isEqualTo(12000L);
        assertThat(result.gpu().metricsComplete()).isTrue();
        assertThat(result.dataStatus()).isEqualTo(TrainingResourceCapabilityDto.DataStatus.AVAILABLE);
        assertThat(result.toString())
                .doesNotContain("internal-control")
                .doesNotContain("internal-worker");
    }

    @Test
    void staleOrMissingGpuDetailsArePartialAndAnExplicitlyBlockedGpuNodeIsExcluded() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer stale = gpuNode("stale", 1);
        ComputeServer blocked = gpuNode("blocked", 1);
        blocked.setK8sLabelsJson("{\"tss.ai/accelerator\":\"nvidia\",\"tss.ai/gpu-schedulable\":\"false\"}");
        when(fixture.serverRepository.findByDeletedFalse()).thenReturn(List.of(stale, blocked));
        fixture.store.update("stale", List.of(
                new GpuDeviceObservationStore.DeviceObservation("RTX", 16384L, 8000L)),
                Instant.now().minus(GpuDeviceObservationStore.MAX_AGE).minusSeconds(1));

        TrainingResourceCapabilityDto result = fixture.service.capability("plan", "v2", "gpu-small");

        assertThat(result.eligibleNodeCount()).isEqualTo(1);
        assertThat(result.gpu().metricsComplete()).isFalse();
        assertThat(result.gpu().safeTotalMemoryMiB()).isNull();
        assertThat(result.dataStatus()).isEqualTo(TrainingResourceCapabilityDto.DataStatus.PARTIAL);
        assertThat(result.message()).contains("暂不允许设置显存预算");
    }

    @Test
    void cpuCapabilityDoesNotDependOnDcgm() {
        TrainingPlanDefinition.ResourceProfile profile = new TrainingPlanDefinition.ResourceProfile(
                "cpu-small", "1", "4", "1Gi", "8Gi", "12Gi", 0,
                Map.of("tss.ai/node-pool", "cpu"));
        Fixture fixture = fixture(profile);
        ComputeServer node = node("cpu-node", 8, 16, 0,
                "{\"tss.ai/node-pool\":\"cpu\"}");
        when(fixture.serverRepository.findByDeletedFalse()).thenReturn(List.of(node));

        TrainingResourceCapabilityDto result = fixture.service.capability("plan", "v2", "cpu-small");

        assertThat(result.dataStatus()).isEqualTo(TrainingResourceCapabilityDto.DataStatus.AVAILABLE);
        assertThat(result.gpu()).isNull();
        assertThat(result.cpu().requestCores()).isEqualTo(1.0);
        assertThat(result.memory().limitMiB()).isEqualTo(8192L);
    }

    private static Fixture fixture(TrainingPlanDefinition.ResourceProfile profile) {
        TrainingPlanDefinition.DeviceType type = profile.gpuCount() > 0
                ? TrainingPlanDefinition.DeviceType.NVIDIA_GPU
                : TrainingPlanDefinition.DeviceType.CPU;
        TrainingPlanDefinition.RuntimeVariant runtime = new TrainingPlanDefinition.RuntimeVariant(
                "runtime", type, "worker:test", TrainingPlanDefinition.ImagePullPolicy.IfNotPresent,
                false, List.of(profile));
        TrainingPlanDefinition plan = mock(TrainingPlanDefinition.class);
        when(plan.id()).thenReturn("plan");
        when(plan.version()).thenReturn("v2");
        TrainingPlanRegistry registry = mock(TrainingPlanRegistry.class);
        when(registry.requireEnabled("plan", "v2")).thenReturn(plan);
        when(registry.resolveRuntime(plan, profile.id()))
                .thenReturn(new TrainingPlanRegistry.ResolvedRuntime(runtime, profile));
        ComputeServerRepository repository = mock(ComputeServerRepository.class);
        GpuDeviceObservationStore store = new GpuDeviceObservationStore();
        TrainingResourceCapabilityService service = new TrainingResourceCapabilityService(
                registry, repository, store, new InferenceModelCacheProperties());
        return new Fixture(repository, store, service);
    }

    private static TrainingPlanDefinition.ResourceProfile gpuProfile() {
        return new TrainingPlanDefinition.ResourceProfile(
                "gpu-small", "1", "4", "2Gi", "8Gi", "12Gi", 1,
                Map.of("tss.ai/accelerator", "nvidia"));
    }

    private static ComputeServer gpuNode(String id, int gpuCount) {
        return node(id, 16, 64, gpuCount,
                "{\"tss.ai/accelerator\":\"nvidia\",\"tss.ai/gpu-schedulable\":\"true\"}");
    }

    private static ComputeServer node(
            String id, double cpu, double memoryGiB, int gpuCount, String labels
    ) {
        ComputeServer server = new ComputeServer();
        server.setServerIp(id);
        server.setK8sNodeName(id);
        server.setHostname(id);
        server.setStatus("online");
        server.setEnabled(true);
        server.setCpuCores(cpu);
        server.setMemoryGib(memoryGiB);
        server.setGpuCount(gpuCount);
        server.setK8sLabelsJson(labels);
        return server;
    }

    private record Fixture(
            ComputeServerRepository serverRepository,
            GpuDeviceObservationStore store,
            TrainingResourceCapabilityService service
    ) {
    }
}
