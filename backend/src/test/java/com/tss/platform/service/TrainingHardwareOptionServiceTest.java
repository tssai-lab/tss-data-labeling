package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.TrainingResourceRequest;
import com.tss.platform.dto.resource.TrainingHardwareOptionDto;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingHardwareOptionServiceTest {

    @Test
    void returnsEveryPhysicalGpuAndResolvesItsUuidAndNode() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer rtx4080 = gpuNode("private-worker-a", "rtx-4080", 2);
        ComputeServer rtx5090 = gpuNode("private-worker-b", "rtx-5090", 1);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(rtx4080, rtx5090));
        Instant now = Instant.now();
        fixture.store.update("private-worker-a", List.of(
                device("NVIDIA GeForce RTX 4080", 16078, 12000),
                device("NVIDIA GeForce RTX 4080", 16078, 10000)), now);
        fixture.store.update("private-worker-b", List.of(
                device("NVIDIA GeForce RTX 5090", 32607, 30000)), now);

        List<TrainingHardwareOptionDto> options = fixture.service.options("plan", "v2");

        assertThat(options).hasSize(3);
        assertThat(options).extracting(option -> option.gpu().model())
                .containsExactlyInAnyOrder(
                        "NVIDIA GeForce RTX 4080",
                        "NVIDIA GeForce RTX 4080",
                        "NVIDIA GeForce RTX 5090");
        assertThat(options).extracting(TrainingHardwareOptionDto::hardwareTargetId)
                .allMatch(id -> id.matches("hw-[0-9a-f]{24}"));
        assertThat(options.get(0).gpu().uuid()).startsWith("GPU-");
        assertThat(options.get(0).gpu().hostGpuIndex()).isNotBlank();

        TrainingHardwareOptionDto selected = options.get(0);
        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setHardwareTargetId(selected.hardwareTargetId());
        request.setCpuCores(2.0);
        request.setMemoryMiB(4096L);
        request.setGpuCount(1);
        request.setGpuMemoryLimitMiB(8192L);

        TrainingHardwareOptionService.HardwareSelection selection = fixture.service.requireSelection(
                fixture.plan, fixture.runtime, fixture.profile, request);

        assertThat(selection.hardwareTargetId()).isEqualTo(selected.hardwareTargetId());
        assertThat(selection.nodeSelector())
                .containsEntry("kubernetes.io/hostname", "private-worker-a");
        assertThat(selection.gpuUuid()).isEqualTo(selected.gpu().uuid());
        assertThat(selection.gpuHostIndex()).isEqualTo(selected.gpu().hostGpuIndex());
    }

    @Test
    void rejectsTamperedTargetAndGpuBudgetAboveTheSelectedModel() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer node = gpuNode("private-worker", "rtx-4080", 1);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(node));
        fixture.store.update("private-worker", List.of(
                device("NVIDIA GeForce RTX 4080", 16078, 12000)), Instant.now());
        String targetId = fixture.service.options("plan", "v2").get(0).hardwareTargetId();

        TrainingResourceRequest tampered = new TrainingResourceRequest();
        tampered.setHardwareTargetId("hw-000000000000000000000000");
        assertThatThrownBy(() -> fixture.service.requireSelection(
                fixture.plan, fixture.runtime, fixture.profile, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可用");

        TrainingResourceRequest tooLarge = new TrainingResourceRequest();
        tooLarge.setHardwareTargetId(targetId);
        tooLarge.setGpuMemoryLimitMiB(16079L);
        assertThatThrownBy(() -> fixture.service.requireSelection(
                fixture.plan, fixture.runtime, fixture.profile, tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds selected hardware memory");
    }

    @Test
    void exactSelectionSafelyOffersUnlabelledMixedGpuModels() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer first = gpuNode("first", null, 1);
        ComputeServer second = gpuNode("second", null, 1);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(first, second));
        fixture.store.update("first", List.of(device("RTX 4080", 16078, 10000)), Instant.now());
        fixture.store.update("second", List.of(device("RTX 5090", 32607, 20000)), Instant.now());

        assertThat(fixture.service.options("plan", "v2")).hasSize(2);
    }

    @Test
    void exactSelectionOnlyOffersCardsWithFreshIdentity() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer observed = gpuNode("observed", null, 1);
        ComputeServer missing = gpuNode("missing", null, 1);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(observed, missing));
        fixture.store.update("observed", List.of(
                device("RTX 4080", 16078, 10000)), Instant.now());

        assertThat(fixture.service.options("plan", "v2")).hasSize(1);
    }

    @Test
    void exactSelectionHidesCardsWithoutAKubernetesNodeName() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer unmapped = gpuNode("unmapped", null, 1);
        unmapped.setK8sNodeName(null);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(unmapped));
        fixture.store.update("unmapped", List.of(
                device("RTX 4080", 16078, 10000)), Instant.now());

        assertThat(fixture.service.options("plan", "v2")).isEmpty();
    }

    @Test
    void exactSelectionDoesNotMergeLabelledAndUnlabelledCards() {
        Fixture fixture = fixture(gpuProfile());
        ComputeServer automatic = gpuNode("automatic", null, 1);
        ComputeServer labelled = gpuNode("labelled", "rtx-4080", 1);
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(automatic, labelled));
        fixture.store.update("automatic", List.of(
                device("RTX 4080", 16078, 10000)), Instant.now());
        fixture.store.update("labelled", List.of(
                device("RTX 4080", 16078, 9999)), Instant.now());

        List<TrainingHardwareOptionDto> options = fixture.service.options("plan", "v2");

        assertThat(options).hasSize(2);
        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setHardwareTargetId(options.get(0).hardwareTargetId());
        assertThat(fixture.service.requireSelection(
                fixture.plan, fixture.runtime, fixture.profile, request).nodeSelector())
                .containsKey("kubernetes.io/hostname");
    }

    @Test
    void cpuOptionUsesDetectedCapacityAsTheCustomUpperBound() {
        Fixture fixture = fixture(cpuProfile());
        ComputeServer node = node("cpu-node", 2, 3, 0,
                "{\"tss.ai/node-pool\":\"cpu\",\"tss.ai/hardware-class\":\"cpu-small\"}");
        node.setSpecCpu("Detected CPU model");
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(node));

        TrainingHardwareOptionDto option = fixture.service.options("plan", "v2").get(0);

        assertThat(option.displayName()).isEqualTo("Detected CPU model");
        assertThat(option.cpu().limitCores()).isEqualTo(2.0);
        assertThat(option.memory().limitMiB()).isEqualTo(3072L);
        assertThat(option.gpu()).isNull();

        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setHardwareTargetId(option.hardwareTargetId());
        request.setCpuCores(3.0);
        assertThatThrownBy(() -> fixture.service.requireSelection(
                fixture.plan, fixture.runtime, fixture.profile, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有节点能满足");
    }

    @Test
    void poolBoundsUseOneConservativeRectangleInsteadOfImpossibleMixedMaxima() {
        Fixture fixture = fixture(cpuProfile());
        ComputeServer cpuRich = node("cpu-rich", 8, 4, 0,
                "{\"tss.ai/node-pool\":\"cpu\",\"tss.ai/hardware-class\":\"cpu-pool\"}");
        ComputeServer memoryRich = node("memory-rich", 2, 16, 0,
                "{\"tss.ai/node-pool\":\"cpu\",\"tss.ai/hardware-class\":\"cpu-pool\"}");
        when(fixture.repository.findByDeletedFalse()).thenReturn(List.of(cpuRich, memoryRich));

        TrainingHardwareOptionDto option = fixture.service.options("plan", "v2").get(0);

        assertThat(option.cpu().limitCores()).isEqualTo(2.0);
        assertThat(option.memory().limitMiB()).isEqualTo(4096L);
    }

    private static Fixture fixture(TrainingPlanDefinition.ResourceProfile profile) {
        TrainingPlanDefinition.DeviceType deviceType = profile.gpuCount() > 0
                ? TrainingPlanDefinition.DeviceType.NVIDIA_GPU
                : TrainingPlanDefinition.DeviceType.CPU;
        TrainingPlanDefinition.RuntimeVariant runtime = new TrainingPlanDefinition.RuntimeVariant(
                "runtime", deviceType, "worker:test",
                TrainingPlanDefinition.ImagePullPolicy.IfNotPresent, false, List.of(profile));
        TrainingPlanDefinition plan = mock(TrainingPlanDefinition.class);
        when(plan.id()).thenReturn("plan");
        when(plan.version()).thenReturn("v2");
        when(plan.runtimes()).thenReturn(List.of(runtime));
        TrainingPlanRegistry registry = mock(TrainingPlanRegistry.class);
        when(registry.requireEnabled("plan", "v2")).thenReturn(plan);
        ComputeServerRepository repository = mock(ComputeServerRepository.class);
        GpuDeviceObservationStore store = new GpuDeviceObservationStore();
        TrainingKubernetesProperties kubernetesProperties = new TrainingKubernetesProperties();
        kubernetesProperties.setExactGpuSelectionEnabled(true);
        TrainingHardwareOptionService service = new TrainingHardwareOptionService(
                registry, repository, store, new GpuDeviceTargetService(store),
                new InferenceModelCacheProperties(), kubernetesProperties);
        return new Fixture(plan, runtime, profile, repository, store, service);
    }

    private static TrainingPlanDefinition.ResourceProfile gpuProfile() {
        return new TrainingPlanDefinition.ResourceProfile(
                "gpu-small", "1", "4", "2Gi", "8Gi", "12Gi", 1,
                Map.of("tss.ai/accelerator", "nvidia"));
    }

    private static TrainingPlanDefinition.ResourceProfile cpuProfile() {
        return new TrainingPlanDefinition.ResourceProfile(
                "cpu-small", "1", "4", "1Gi", "8Gi", "12Gi", 0,
                Map.of("tss.ai/node-pool", "cpu"));
    }

    private static ComputeServer gpuNode(String id, String hardwareClass, int gpuCount) {
        String classLabel = hardwareClass == null ? ""
                : ",\"tss.ai/hardware-class\":\"" + hardwareClass + "\"";
        return node(id, 16, 64, gpuCount,
                "{\"tss.ai/accelerator\":\"nvidia\",\"tss.ai/gpu-schedulable\":\"true\""
                        + classLabel + "}");
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

    private static GpuDeviceObservationStore.DeviceObservation device(
            String model, long totalMemoryMiB, long freeMemoryMiB
    ) {
        return new GpuDeviceObservationStore.DeviceObservation(
                String.valueOf(Math.abs((model + freeMemoryMiB).hashCode()) % 8),
                "GPU-" + Integer.toUnsignedString((model + totalMemoryMiB + freeMemoryMiB).hashCode(), 16)
                        + "abcd1234",
                model, totalMemoryMiB, freeMemoryMiB, 0.0, 40.0);
    }

    private record Fixture(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            ComputeServerRepository repository,
            GpuDeviceObservationStore store,
            TrainingHardwareOptionService service
    ) {
    }
}
