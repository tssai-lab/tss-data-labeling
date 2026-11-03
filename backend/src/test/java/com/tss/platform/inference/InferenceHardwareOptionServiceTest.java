package com.tss.platform.inference;

import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.service.GpuDeviceObservationStore;
import com.tss.platform.service.GpuDeviceTargetService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InferenceHardwareOptionServiceTest {

    @Test
    void returnsOneOptionPerPhysicalCardAndRejectsTampering() {
        TrainingKubernetesProperties kubernetes = new TrainingKubernetesProperties();
        kubernetes.setExactGpuSelectionEnabled(true);
        InferenceResourceProfileService profiles = new InferenceResourceProfileService(
                new InferenceKubernetesResourceProperties(), kubernetes);
        ComputeServerRepository repository = mock(ComputeServerRepository.class);
        ComputeServer node = new ComputeServer();
        node.setServerIp("gpu-worker");
        node.setK8sNodeName("gpu-worker");
        node.setHostname("seu4080");
        node.setStatus("online");
        node.setEnabled(true);
        node.setGpuCount(2);
        node.setK8sLabelsJson("{\"tss.ai/gpu-schedulable\":\"true\"}");
        when(repository.findByDeletedFalse()).thenReturn(List.of(node));
        GpuDeviceObservationStore store = new GpuDeviceObservationStore();
        store.update("gpu-worker", List.of(
                new GpuDeviceObservationStore.DeviceObservation(
                        "0", "GPU-aaaaaaaa", "RTX 4080", 16384L, 12000L, 10.0, 45.0),
                new GpuDeviceObservationStore.DeviceObservation(
                        "1", "GPU-bbbbbbbb", "RTX 4080", 16384L, 14000L, 0.0, 40.0)
        ), Instant.now());
        InferenceHardwareOptionService service = new InferenceHardwareOptionService(
                repository,
                new GpuDeviceTargetService(store),
                profiles,
                new InferenceModelCacheProperties());

        var options = service.listOptions();

        assertThat(options).hasSize(2);
        var selected = service.requireForCreate(
                profiles.resolveForCreate("gpu-one"),
                options.get(1).hardwareTargetId(),
                8192L);
        assertThat(selected.gpuUuid()).isEqualTo("GPU-bbbbbbbb");
        assertThat(selected.hostGpuIndex()).isEqualTo("1");
        assertThatThrownBy(() -> service.requireForCreate(
                profiles.resolveForCreate("gpu-one"), "hw-tampered", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
