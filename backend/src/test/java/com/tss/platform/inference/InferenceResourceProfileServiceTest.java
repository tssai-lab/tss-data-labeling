package com.tss.platform.inference;

import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceResourceProfileServiceTest {

    @Test
    void exposesOnlyConfiguredCpuProfile() {
        InferenceKubernetesResourceProperties properties = new InferenceKubernetesResourceProperties();
        properties.setCpuRequest("750m");
        properties.setMemoryLimit("3Gi");
        InferenceResourceProfileService service = new InferenceResourceProfileService(properties);

        var profiles = service.listEnabledProfiles();

        assertEquals(1, profiles.size());
        assertEquals("cpu-small", profiles.get(0).id());
        assertEquals("CPU", profiles.get(0).deviceType());
        assertEquals("750m", profiles.get(0).cpuRequest());
        assertEquals("3Gi", profiles.get(0).memoryLimit());
        assertEquals(0, profiles.get(0).gpuCount());
    }

    @Test
    void oldClientAndHistoricalTaskUseCpuDefault() {
        InferenceResourceProfileService service = new InferenceResourceProfileService(
                new InferenceKubernetesResourceProperties()
        );

        assertEquals("cpu-small", service.resolveForCreate(null).id());
        assertEquals("cpu-small", service.resolveForCreate("  ").id());
        assertEquals("cpu-small", service.resolveForExecution(null).id());
    }

    @Test
    void rejectsProfilesOutsideServerWhitelist() {
        InferenceResourceProfileService service = new InferenceResourceProfileService(
                new InferenceKubernetesResourceProperties()
        );

        assertThrows(IllegalArgumentException.class, () -> service.resolveForCreate("gpu-1"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveForExecution("cpu-unbounded"));
    }

    @Test
    void exposesSingleGpuProfileOnlyWhenExactSelectionUsesKubectl() {
        TrainingKubernetesProperties kubernetes = new TrainingKubernetesProperties();
        kubernetes.setExactGpuSelectionEnabled(true);
        InferenceResourceProfileService service = new InferenceResourceProfileService(
                new InferenceKubernetesResourceProperties(), kubernetes);

        assertEquals("gpu-one", service.listEnabledProfiles().get(1).id());
        assertEquals("NVIDIA_GPU", service.resolveForCreate("gpu-one").deviceType());
        assertEquals(1, service.resolveForCreate("gpu-one").gpuCount());

        kubernetes.setClientMode(TrainingKubernetesProperties.ClientMode.FABRIC8);
        assertThrows(IllegalArgumentException.class, () -> service.resolveForCreate("gpu-one"));
    }
}
