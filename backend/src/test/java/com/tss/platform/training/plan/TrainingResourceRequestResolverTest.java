package com.tss.platform.training.plan;

import com.tss.platform.dto.TrainingResourceRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingResourceRequestResolverTest {

    @Test
    void missingOverridePreservesThePlanProfileExactly() {
        TrainingRunSpec.Resources resolved = TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), null);

        assertThat(resolved.cpuRequest()).isEqualTo("500m");
        assertThat(resolved.cpuLimit()).isEqualTo("4");
        assertThat(resolved.memoryRequest()).isEqualTo("1Gi");
        assertThat(resolved.memoryLimit()).isEqualTo("8Gi");
        assertThat(resolved.ephemeralStorageLimit()).isEqualTo("12Gi");
        assertThat(resolved.gpuMemoryLimitMiB()).isNull();
    }

    @Test
    void customCpuAndMemoryBecomeTheRequestedAndLimitedAmount() {
        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setCpuCores(2.5);
        request.setMemoryMiB(4096L);
        request.setGpuCount(0);

        TrainingRunSpec.Resources resolved = TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), request);

        assertThat(resolved.cpuRequest()).isEqualTo("2.5");
        assertThat(resolved.cpuLimit()).isEqualTo("2.5");
        assertThat(resolved.memoryRequest()).isEqualTo("4096Mi");
        assertThat(resolved.memoryLimit()).isEqualTo("4096Mi");
    }

    @Test
    void persistsOpaqueHardwareTargetAndTrustedSelectorInTheRunSpec() {
        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setHardwareTargetId("hw-0123456789abcdef01234567");
        request.setGpuCount(1);

        TrainingRunSpec.Resources resolved = TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.NVIDIA_GPU, gpuProfile()),
                gpuProfile(), request, request.getHardwareTargetId(),
                Map.of("tss.ai/hardware-class", "rtx-4080"));

        assertThat(resolved.hardwareTargetId()).isEqualTo(request.getHardwareTargetId());
        assertThat(resolved.nodeSelector())
                .containsEntry("tss.ai/accelerator", "nvidia")
                .containsEntry("tss.ai/hardware-class", "rtx-4080")
                .doesNotContainKey("kubernetes.io/hostname");
    }

    @Test
    void rejectsValuesOutsideThePlanAndGpuFieldsOnCpu() {
        TrainingResourceRequest belowMinimum = new TrainingResourceRequest();
        belowMinimum.setCpuCores(0.25);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), belowMinimum))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the selected profile bounds");

        TrainingResourceRequest gpuBudget = new TrainingResourceRequest();
        gpuBudget.setGpuMemoryLimitMiB(1024L);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), gpuBudget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only valid for GPU training");
    }

    @Test
    void rejectsNonFiniteCpuAndInvalidMemoryWithoutProducingARunSpec() {
        TrainingResourceRequest nonFiniteCpu = new TrainingResourceRequest();
        nonFiniteCpu.setCpuCores(Double.NaN);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), nonFiniteCpu))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite positive number");

        TrainingResourceRequest zeroMemory = new TrainingResourceRequest();
        zeroMemory.setMemoryMiB(0L);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), zeroMemory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        TrainingResourceRequest overflowingMemory = new TrainingResourceRequest();
        overflowingMemory.setMemoryMiB(Long.MAX_VALUE);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.CPU, cpuProfile()), cpuProfile(), overflowingMemory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
    }

    @Test
    void acceptsPositivePerGpuBudgetButNeverChangesThePlanGpuCount() {
        TrainingPlanDefinition.ResourceProfile profile = gpuProfile();
        TrainingResourceRequest request = new TrainingResourceRequest();
        request.setGpuCount(1);
        request.setGpuMemoryLimitMiB(8192L);

        TrainingRunSpec.Resources resolved = TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.NVIDIA_GPU, profile), profile, request);

        assertThat(resolved.gpuCount()).isEqualTo(1);
        assertThat(resolved.gpuMemoryLimitMiB()).isEqualTo(8192L);

        request.setGpuCount(2);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.NVIDIA_GPU, profile), profile, request))
                .hasMessageContaining("must match the selected profile");

        request.setGpuCount(1);
        request.setGpuMemoryLimitMiB(0L);
        assertThatThrownBy(() -> TrainingResourceRequestResolver.resolve(
                runtime(TrainingPlanDefinition.DeviceType.NVIDIA_GPU, profile), profile, request))
                .hasMessageContaining("must be a positive integer");
    }

    private static TrainingPlanDefinition.RuntimeVariant runtime(
            TrainingPlanDefinition.DeviceType deviceType,
            TrainingPlanDefinition.ResourceProfile profile
    ) {
        return new TrainingPlanDefinition.RuntimeVariant(
                "runtime", deviceType, "worker:test",
                TrainingPlanDefinition.ImagePullPolicy.IfNotPresent, false, List.of(profile));
    }

    private static TrainingPlanDefinition.ResourceProfile cpuProfile() {
        return new TrainingPlanDefinition.ResourceProfile(
                "cpu-small", "500m", "4", "1Gi", "8Gi", "12Gi", 0,
                Map.of("tss.ai/node-pool", "cpu"));
    }

    private static TrainingPlanDefinition.ResourceProfile gpuProfile() {
        return new TrainingPlanDefinition.ResourceProfile(
                "gpu-small", "1", "4", "2Gi", "8Gi", "12Gi", 1,
                Map.of("tss.ai/accelerator", "nvidia"));
    }
}
