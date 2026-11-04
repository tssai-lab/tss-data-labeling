package com.tss.platform.training;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.service.JobTtlPolicyService;
import com.tss.platform.service.ModelCachePolicyService;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingRunSpec;
import com.tss.platform.training.plan.TrainingRunSpecCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubernetesJobManifestBuilderTest {

    @Test
    void containerStartsAtWorkspaceRootSoNonRootWorkerCanCreateCodeDirectory() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("test-callback-token");

        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        when(task.getId()).thenReturn("train-version-test");
        when(task.getRunSpecJson()).thenReturn("{}");
        when(task.getRunSpecSha256()).thenReturn("a".repeat(64));

        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingPlanDefinition.Security security = mock(TrainingPlanDefinition.Security.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        when(runSpec.security()).thenReturn(security);
        when(security.maxRuntimeSeconds()).thenReturn(3600);
        when(security.runAsNonRoot()).thenReturn(true);
        when(security.allowPrivilegeEscalation()).thenReturn(false);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.nodeSelector()).thenReturn(Map.of());
        when(resources.ephemeralStorageLimit()).thenReturn("1Gi");
        when(resources.cpuRequest()).thenReturn("1");
        when(resources.cpuLimit()).thenReturn("2");
        when(resources.memoryRequest()).thenReturn("1Gi");
        when(resources.memoryLimit()).thenReturn("2Gi");
        when(resources.gpuCount()).thenReturn(0);
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.CPU);
        when(runtime.imagePullPolicy()).thenReturn(TrainingPlanDefinition.ImagePullPolicy.IfNotPresent);

        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);
        TrainingRuntimeImageService runtimeImageService = mock(TrainingRuntimeImageService.class);
        when(runtimeImageService.resolveImage(runSpec)).thenReturn("registry.example/training-worker:test");

        JobTtlPolicyService ttlPolicyService = mock(JobTtlPolicyService.class);
        when(ttlPolicyService.currentJobTtlSecondsAfterFinished()).thenReturn(180);
        KubernetesJobManifestBuilder builder =
                new KubernetesJobManifestBuilder(properties, codec, runtimeImageService);
        builder.setJobTtlPolicyService(ttlPolicyService);

        String yaml = builder.buildJobYaml(task, "access", "secret", "models", null);

        assertTrue(yaml.contains("workingDir: /workspace/job\n"));
        assertFalse(yaml.contains("workingDir: /workspace/job/code\n"));
        assertFalse(yaml.contains("nvidia.com/gpu:"));
        assertFalse(yaml.contains("runtimeClassName:"));
        assertTrue(yaml.contains("ttlSecondsAfterFinished: 180\n"));
    }

    @Test
    void gpuRunUsesDraClaimForTheSelectedUuid() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("test-callback-token");
        properties.setExactGpuSelectionEnabled(true);

        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        when(task.getId()).thenReturn("train-gpu-test");
        when(task.getRunSpecJson()).thenReturn("{}");
        when(task.getRunSpecSha256()).thenReturn("c".repeat(64));

        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingPlanDefinition.Security security = mock(TrainingPlanDefinition.Security.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        when(runSpec.security()).thenReturn(security);
        when(security.maxRuntimeSeconds()).thenReturn(3600);
        when(security.runAsNonRoot()).thenReturn(true);
        when(security.allowPrivilegeEscalation()).thenReturn(false);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.nodeSelector()).thenReturn(Map.of());
        when(resources.ephemeralStorageLimit()).thenReturn("1Gi");
        when(resources.cpuRequest()).thenReturn("1");
        when(resources.cpuLimit()).thenReturn("2");
        when(resources.memoryRequest()).thenReturn("1Gi");
        when(resources.memoryLimit()).thenReturn("2Gi");
        when(resources.gpuCount()).thenReturn(1);
        when(resources.gpuMemoryLimitMiB()).thenReturn(8192L);
        when(resources.gpuUuid()).thenReturn("GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(resources.gpuHostIndex()).thenReturn("1");
        when(resources.gpuNodeName()).thenReturn("seu4080");
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.NVIDIA_GPU);
        when(runtime.imagePullPolicy()).thenReturn(TrainingPlanDefinition.ImagePullPolicy.IfNotPresent);

        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);
        TrainingRuntimeImageService runtimeImageService = mock(TrainingRuntimeImageService.class);
        when(runtimeImageService.resolveImage(runSpec)).thenReturn("registry.example/training-worker:gpu-test");
        KubernetesJobManifestBuilder builder =
                new KubernetesJobManifestBuilder(properties, codec, runtimeImageService);

        String yaml = builder.buildJobYaml(task, "access", "secret", "models", "seu4080");

        assertTrue(yaml.contains("kind: ResourceClaimTemplate"));
        assertTrue(yaml.contains("deviceClassName: gpu.nvidia.com"));
        assertTrue(yaml.contains("uuid == 'GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'"));
        assertTrue(yaml.contains("resourceClaimTemplateName: tss-gpu-"));
        assertTrue(yaml.contains("claims:\n              - name: gpu"));
        assertFalse(yaml.contains("nvidia.com/gpu:"));
        assertTrue(yaml.contains("runtimeClassName: nvidia"));
        assertTrue(yaml.contains("kubernetes.io/hostname: \"seu4080\""));
        assertFalse(yaml.contains("nodeName: seu4080"));
        assertTrue(yaml.contains("key: node-role.kubernetes.io/control-plane"));
        assertTrue(yaml.contains("key: nvidia.com/gpu"));
        assertTrue(yaml.contains("name: TSS_GPU_MEMORY_LIMIT_MIB"));
        assertTrue(yaml.contains("value: \"8192\""));
        assertTrue(yaml.contains("name: TSS_SELECTED_GPU_UUID"));
        assertFalse(yaml.contains("activeDeadlineSeconds:"));
        assertTrue(yaml.contains("- /usr/bin/timeout"));
        assertTrue(yaml.contains("- /app/train.py"));
    }

    @Test
    void enabledCachePreparesTrainingModelAndMountsOnlyDigestReadOnly() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("test-callback-token");
        InferenceModelCacheProperties cacheProperties = new InferenceModelCacheProperties();
        cacheProperties.setEnabled(true);
        cacheProperties.setMaxBytes(4096);
        cacheProperties.setMinFreeBytes(128);

        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        when(task.getId()).thenReturn("train-cache-test");
        when(task.getRunSpecJson()).thenReturn("{}");
        when(task.getRunSpecSha256()).thenReturn("b".repeat(64));

        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingPlanDefinition.Security security = mock(TrainingPlanDefinition.Security.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        String digest = "a".repeat(64);
        TrainingRunSpec.InputArtifact model = new TrainingRunSpec.InputArtifact(
                "model-version-1",
                "users/7/models/model.zip",
                digest,
                512L,
                "MODEL_ZIP",
                "model.zip",
                true,
                java.util.List.of("config.json")
        );
        when(runSpec.inputs()).thenReturn(new TrainingRunSpec.Inputs(model, null, null));
        when(runSpec.security()).thenReturn(security);
        when(security.maxRuntimeSeconds()).thenReturn(3600);
        when(security.runAsNonRoot()).thenReturn(true);
        when(security.allowPrivilegeEscalation()).thenReturn(false);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.nodeSelector()).thenReturn(Map.of());
        when(resources.ephemeralStorageLimit()).thenReturn("1Gi");
        when(resources.cpuRequest()).thenReturn("1");
        when(resources.cpuLimit()).thenReturn("2");
        when(resources.memoryRequest()).thenReturn("1Gi");
        when(resources.memoryLimit()).thenReturn("2Gi");
        when(resources.gpuCount()).thenReturn(0);
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.CPU);
        when(runtime.imagePullPolicy()).thenReturn(TrainingPlanDefinition.ImagePullPolicy.IfNotPresent);

        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);
        TrainingRuntimeImageService runtimeImageService = mock(TrainingRuntimeImageService.class);
        when(runtimeImageService.resolveImage(runSpec)).thenReturn("registry.example/training-worker:test");
        KubernetesJobManifestBuilder builder =
                new KubernetesJobManifestBuilder(properties, codec, runtimeImageService);
        builder.setModelCacheProperties(cacheProperties);
        ModelCachePolicyService policyService = mock(ModelCachePolicyService.class);
        when(policyService.currentPolicy()).thenReturn(
                new ModelCachePolicy(8192, 256, 1024, null)
        );
        builder.setModelCachePolicyService(policyService);

        String yaml = builder.buildJobYaml(
                task, "access", "secret", "models", "kind-worker");

        assertTrue(yaml.contains("name: model-cache-initializer"));
        assertTrue(yaml.contains("persistentVolumeClaim:"));
        assertTrue(yaml.contains("claimName: \"tss-model-cache-kind-worker\""));
        assertFalse(yaml.contains("hostPath:"));
        assertTrue(yaml.contains("value: \"prepare-model-cache\""));
        assertTrue(yaml.contains("subPath: \"entries/" + digest + "/data\""));
        assertTrue(yaml.contains("subPath: \"locks/" + digest + ".lock\""));
        assertTrue(yaml.contains("mountPath: /workspace/job/model"));
        assertTrue(yaml.contains("readOnly: true"));
        assertTrue(yaml.contains("name: MODEL_CACHE_ENABLED"));
        assertTrue(yaml.contains("nodeName: kind-worker"));
        assertTrue(yaml.contains("name: MODEL_CACHE_MAX_BYTES\n              value: \"8192\""));
        assertTrue(yaml.contains("name: MODEL_CACHE_MIN_FREE_BYTES\n              value: \"256\""));
    }

    @Test
    void rejectsGpuCountOnCpuRuntimeBeforeCreatingAJob() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.CPU);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.gpuCount()).thenReturn(1);
        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);

        KubernetesJobManifestBuilder builder = new KubernetesJobManifestBuilder(
                properties, codec, mock(TrainingRuntimeImageService.class));

        assertThrows(IllegalStateException.class,
                () -> builder.buildJobYaml(task, "access", "secret", "models", null));
    }

    @Test
    void rejectsGpuRuntimeWithoutAGpuRequestBeforeCreatingAJob() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.NVIDIA_GPU);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.gpuCount()).thenReturn(0);
        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);

        KubernetesJobManifestBuilder builder = new KubernetesJobManifestBuilder(
                properties, codec, mock(TrainingRuntimeImageService.class));

        assertThrows(IllegalStateException.class,
                () -> builder.buildJobYaml(task, "access", "secret", "models", null));
    }

    @Test
    void rejectsMultiGpuRequestUntilDistributedTrainingIsImplemented() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        TrainingRunSpec runSpec = mock(TrainingRunSpec.class);
        TrainingRunSpec.Runtime runtime = mock(TrainingRunSpec.Runtime.class);
        TrainingRunSpec.Resources resources = mock(TrainingRunSpec.Resources.class);
        when(runSpec.runtime()).thenReturn(runtime);
        when(runtime.deviceType()).thenReturn(TrainingPlanDefinition.DeviceType.NVIDIA_GPU);
        when(runSpec.resources()).thenReturn(resources);
        when(resources.gpuCount()).thenReturn(2);
        TrainingRunSpecCodec codec = mock(TrainingRunSpecCodec.class);
        when(codec.decode(task)).thenReturn(runSpec);

        KubernetesJobManifestBuilder builder = new KubernetesJobManifestBuilder(
                properties, codec, mock(TrainingRuntimeImageService.class));

        assertThrows(IllegalStateException.class,
                () -> builder.buildJobYaml(task, "access", "secret", "models", null));
    }
}
