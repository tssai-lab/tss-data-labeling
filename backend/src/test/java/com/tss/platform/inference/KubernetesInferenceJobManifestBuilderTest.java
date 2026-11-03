package com.tss.platform.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.service.JobTtlPolicyService;
import com.tss.platform.service.ModelCachePolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubernetesInferenceJobManifestBuilderTest {

    @Test
    void manifestUsesTheTaskResourceProfileLimits() throws Exception {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("internal-token");
        InferenceKubernetesResourceProperties resourceProperties =
                new InferenceKubernetesResourceProperties();
        resourceProperties.setCpuRequest("750m");
        resourceProperties.setCpuLimit("3");
        resourceProperties.setMemoryRequest("1Gi");
        resourceProperties.setMemoryLimit("5Gi");
        resourceProperties.setEphemeralStorageRequest("3Gi");
        resourceProperties.setEphemeralStorageLimit("9Gi");
        KubernetesInferenceJobManifestBuilder builder =
                new KubernetesInferenceJobManifestBuilder(
                        properties,
                        new InferenceModelCacheProperties(),
                        new InferenceResourceProfileService(resourceProperties)
                );

        InferenceTask task = new InferenceTask();
        task.setId("infer-resource-1");
        task.setCurrentAttempt(1);
        task.setResourceProfileId("cpu-small");
        task.setModelVersionId("model-ver-1");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode("SINGLE_OBJECT");
        task.setParamsJson("{}");

        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setStoragePath("users/7/models/model.zip");
        InferenceScriptVersion scriptVersion = new InferenceScriptVersion();
        scriptVersion.setStoragePath("users/7/scripts/script.zip");
        scriptVersion.setEntryFile("infer.py");

        String yaml = builder.buildJobYaml(
                task, modelVersion, scriptVersion, null, "access", "secret", "models"
        );
        JsonNode resources = YAMLMapper.builder().build().readTree(yaml)
                .path("spec").path("template").path("spec")
                .path("containers").path(0).path("resources");

        assertEquals("750m", resources.path("requests").path("cpu").asText());
        assertEquals("1Gi", resources.path("requests").path("memory").asText());
        assertEquals("3Gi", resources.path("requests").path("ephemeral-storage").asText());
        assertEquals("3", resources.path("limits").path("cpu").asText());
        assertEquals("5Gi", resources.path("limits").path("memory").asText());
        assertEquals("9Gi", resources.path("limits").path("ephemeral-storage").asText());
    }

    @Test
    void manifestUsesAttemptScopedJobCallbackAndOutputPrefix() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("internal-token");
        KubernetesInferenceJobManifestBuilder builder =
                new KubernetesInferenceJobManifestBuilder(
                        properties,
                        new InferenceModelCacheProperties(),
                        resourceProfileService());
        JobTtlPolicyService ttlPolicyService = mock(JobTtlPolicyService.class);
        when(ttlPolicyService.currentJobTtlSecondsAfterFinished()).thenReturn(180);
        builder.setJobTtlPolicyService(ttlPolicyService);

        InferenceTask task = new InferenceTask();
        task.setId("infer-task-abc");
        task.setOwnerUserId(7);
        task.setCurrentAttempt(2);
        task.setModelVersionId("model-ver-1");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode("SINGLE_OBJECT");
        task.setInputObjectName("users/7/files/input.jpg");
        task.setParamsJson("{}");

        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setStoragePath("users/7/models/model.zip");

        InferenceScriptVersion scriptVersion = new InferenceScriptVersion();
        scriptVersion.setStoragePath("users/7/scripts/script.zip");
        scriptVersion.setEntryFile("infer.py");

        String yaml = builder.buildJobYaml(
                task,
                modelVersion,
                scriptVersion,
                null,
                "access",
                "secret",
                "models"
        );

        assertTrue(yaml.contains("name: tss-infer-infer-task-abc-a2"));
        assertTrue(yaml.contains("/api/internal/inference/result?id=infer-task-abc&attempt=2"));
        assertTrue(yaml.contains("name: INFERENCE_ATTEMPT"));
        assertTrue(yaml.contains("value: \"2\""));
        assertTrue(yaml.contains("users/7/inference-results/infer-task-abc/attempt-2"));
        assertTrue(yaml.contains("ttlSecondsAfterFinished: 180"));
        assertFalse(yaml.contains("model-cache-initializer"));
        assertFalse(yaml.contains("tss.ai/model-cache-ready"));
    }

    @Test
    void enabledCacheUsesAttestedDigestAndExposesOnlyRequestedModelReadOnly() throws Exception {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("internal-token");
        InferenceModelCacheProperties cacheProperties = new InferenceModelCacheProperties();
        cacheProperties.setEnabled(true);
        cacheProperties.setNodePath("/var/lib/tss-platform/model-cache");
        cacheProperties.setMountPath("/var/cache/tss/models");
        cacheProperties.setMaxBytes(1024);
        cacheProperties.setMinFreeBytes(128);
        KubernetesInferenceJobManifestBuilder builder =
                new KubernetesInferenceJobManifestBuilder(
                        properties, cacheProperties, resourceProfileService());
        ModelCachePolicyService policyService = mock(ModelCachePolicyService.class);
        when(policyService.currentPolicy()).thenReturn(
                new ModelCachePolicy(2048, 256, 1024, null)
        );
        builder.setModelCachePolicyService(policyService);

        InferenceTask task = new InferenceTask();
        task.setId("infer-cache-1");
        task.setOwnerUserId(7);
        task.setCurrentAttempt(1);
        task.setModelVersionId("model-ver-1");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode("SINGLE_OBJECT");
        task.setInputObjectName("users/7/files/input.jpg");
        task.setParamsJson("{}");

        String digest = "a".repeat(64);
        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setId("model-ver-1");
        modelVersion.setStoragePath("users/7/models/model.zip");
        modelVersion.setSizeBytes(512L);
        modelVersion.setArtifactAttestedSha256(digest);

        InferenceScriptVersion scriptVersion = new InferenceScriptVersion();
        scriptVersion.setStoragePath("users/7/scripts/script.zip");
        scriptVersion.setEntryFile("infer.py");

        String yaml = builder.buildJobYaml(
                task,
                modelVersion,
                scriptVersion,
                null,
                "access",
                "secret",
                "models",
                "worker-2"
        );

        assertTrue(yaml.contains("name: model-cache-initializer"));
        assertTrue(yaml.contains("persistentVolumeClaim:"));
        assertTrue(yaml.contains("claimName: \"tss-model-cache-worker-2\""));
        assertFalse(yaml.contains("hostPath:"));
        assertTrue(yaml.contains("value: \"prepare-model-cache\""));
        assertTrue(yaml.contains("value: \"" + digest + "\""));
        assertTrue(yaml.contains("subPath: \"entries/" + digest + "/data\""));
        assertTrue(yaml.contains("subPath: \"locks/" + digest + ".lock\""));
        assertTrue(yaml.contains("mountPath: /workspace/job/model"));
        assertTrue(yaml.contains("mountPath: /var/run/tss-model-cache/model.lock"));
        assertTrue(yaml.contains("readOnly: true"));
        assertTrue(yaml.contains("name: MODEL_CACHE_ENABLED"));
        assertTrue(yaml.contains("nodeName: \"worker-2\""));
        assertTrue(yaml.contains("name: MODEL_CACHE_MAX_BYTES\n              value: \"2048\""));
        assertTrue(yaml.contains("name: MODEL_CACHE_MIN_FREE_BYTES\n              value: \"256\""));

        JsonNode podSpec = YAMLMapper.builder().build().readTree(yaml)
                .path("spec").path("template").path("spec");
        assertEquals(1, podSpec.path("initContainers").size());
        assertEquals(1, podSpec.path("containers").size());
        assertEquals(2, podSpec.path("volumes").size());
        assertEquals(
                "tss-model-cache-worker-2",
                podSpec.path("volumes").path(1).path("persistentVolumeClaim")
                        .path("claimName").asText());

        String pinnedYaml = builder.buildJobYaml(
                task,
                modelVersion,
                scriptVersion,
                null,
                "access",
                "secret",
                "models",
                "worker-2"
        );
        JsonNode pinnedSpec = YAMLMapper.builder().build().readTree(pinnedYaml)
                .path("spec").path("template").path("spec");
        assertEquals("worker-2", pinnedSpec.path("nodeName").asText());
        assertTrue(pinnedSpec.path("nodeSelector").isMissingNode());
    }

    @Test
    void enabledCacheRejectsUnsafeNodePath() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("internal-token");
        InferenceModelCacheProperties cacheProperties = new InferenceModelCacheProperties();
        cacheProperties.setEnabled(true);
        cacheProperties.setNodePath("/var/lib/../etc");
        KubernetesInferenceJobManifestBuilder builder =
                new KubernetesInferenceJobManifestBuilder(
                        properties, cacheProperties, resourceProfileService());

        InferenceTask task = new InferenceTask();
        task.setId("infer-cache-invalid");
        task.setOwnerUserId(7);
        task.setModelVersionId("model-ver-1");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode("SINGLE_OBJECT");

        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setId("model-ver-1");
        modelVersion.setStoragePath("users/7/models/model.zip");
        modelVersion.setSizeBytes(512L);
        modelVersion.setArtifactAttestedSha256("b".repeat(64));

        InferenceScriptVersion scriptVersion = new InferenceScriptVersion();
        scriptVersion.setStoragePath("users/7/scripts/script.zip");
        scriptVersion.setEntryFile("infer.py");

        assertThrows(
                IllegalStateException.class,
                () -> builder.buildJobYaml(
                        task, modelVersion, scriptVersion, null, "access", "secret", "models")
        );
    }

    @Test
    void gpuInferenceUsesSelectedDraClaimImageAndSoftMemoryBudget() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("internal-token");
        properties.setExactGpuSelectionEnabled(true);
        InferenceResourceProfileService profiles = new InferenceResourceProfileService(
                new InferenceKubernetesResourceProperties(), properties);
        KubernetesInferenceJobManifestBuilder builder = new KubernetesInferenceJobManifestBuilder(
                properties, new InferenceModelCacheProperties(), profiles);
        ReflectionTestUtils.setField(builder, "workerImage", "cpu-worker:test");
        ReflectionTestUtils.setField(builder, "gpuWorkerImage", "gpu-worker:test");
        ReflectionTestUtils.setField(builder, "workerImagePullPolicy", "IfNotPresent");

        InferenceTask task = new InferenceTask();
        task.setId("infer-gpu-1");
        task.setCurrentAttempt(1);
        task.setResourceProfileId("gpu-one");
        task.setModelVersionId("model-ver-1");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode("SINGLE_OBJECT");
        task.setGpuNodeName("worker-gpu");
        task.setGpuHostIndex("1");
        task.setGpuUuid("GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        task.setGpuMemoryLimitMiB(4096L);

        ModelVersion model = new ModelVersion();
        model.setStoragePath("users/7/models/model.zip");
        InferenceScriptVersion script = new InferenceScriptVersion();
        script.setStoragePath("users/7/scripts/script.zip");
        script.setEntryFile("infer.py");

        String yaml = builder.buildJobYaml(
                task, model, script, null, "access", "secret", "models", "worker-gpu");

        assertTrue(yaml.contains("kind: ResourceClaimTemplate"));
        assertTrue(yaml.contains("image: gpu-worker:test"));
        assertTrue(yaml.contains("imagePullPolicy: IfNotPresent"));
        assertTrue(yaml.contains("resourceClaimTemplateName: tss-gpu-"));
        assertTrue(yaml.contains("name: TSS_SELECTED_GPU_UUID"));
        assertTrue(yaml.contains("value: \"GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\""));
        assertTrue(yaml.contains("name: TSS_GPU_MEMORY_LIMIT_MIB"));
        assertTrue(yaml.contains("value: \"4096\""));
        assertTrue(yaml.contains("claims:\n              - name: gpu"));
        assertFalse(yaml.contains("nvidia.com/gpu:"));
        assertTrue(yaml.contains("kubernetes.io/hostname: \"worker-gpu\""));
        assertFalse(yaml.contains("nodeName: \"worker-gpu\""));
        assertTrue(yaml.contains("key: node-role.kubernetes.io/control-plane"));
        assertTrue(yaml.contains("key: nvidia.com/gpu"));
        assertFalse(yaml.contains("activeDeadlineSeconds:"));
        assertTrue(yaml.contains("- /usr/bin/timeout"));
        assertTrue(yaml.contains("- /app/infer_worker.py"));
    }

    private static InferenceResourceProfileService resourceProfileService() {
        return new InferenceResourceProfileService(new InferenceKubernetesResourceProperties());
    }
}
