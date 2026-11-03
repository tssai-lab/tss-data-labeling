package com.tss.platform.training.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.asset.spec.ArtifactSpecRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanRegistryTest {

    @Test
    void loadsBuiltInPlansWithTheirDeclaredExecutionContracts() {
        TrainingPlanRegistry registry = new TrainingPlanRegistry(
                new TrainingPlanValidator(new ArtifactSpecRegistry()),
                new TrainingPlanYamlParser()
        );
        registry.initialize();

        TrainingPlanDefinition yolo = registry.require("yolo_object_detection", "v1");
        assertFalse(yolo.enabled(), "GPU plan must remain unavailable until its immutable image passes smoke");
        assertEquals("train.py", yolo.execution().entrypoint());
        assertTrue(yolo.trainingModes().contains(TrainingPlanDefinition.TrainingMode.FULL_FINETUNE));
        assertEquals(1, yolo.runtimes().get(0).resourceProfiles().get(0).gpuCount());
        assertEquals("nvidia", yolo.runtimes().get(0).resourceProfiles().get(0)
                .nodeSelector().get("tss.ai/accelerator"));
        assertEquals("best.pt", yolo.outputs().artifacts().stream()
                .filter(artifact -> Boolean.TRUE.equals(artifact.publishAsModel()))
                .findFirst().orElseThrow().path());

        TrainingPlanDefinition baseline = registry.requireEnabled(
                "image_text_consistency_fusion_logreg", "v1"
        );
        assertEquals("scripts/training/train_fusion_baseline.py", baseline.execution().entrypoint());

        TrainingPlanDefinition huggingFace = registry.requireEnabled(
                "hf_image_classification", "v1"
        );
        assertEquals("train.py", huggingFace.execution().entrypoint());
        assertEquals("mobilenet_beans_model.zip", huggingFace.outputs().artifacts().stream()
                .filter(artifact -> Boolean.TRUE.equals(artifact.publishAsModel()))
                .findFirst().orElseThrow().path());
        assertTrue(huggingFace.inputs().dataset().annotationFormats().contains("FOLDER_CLASSIFICATION"));

        TrainingPlanDefinition miniRbt = registry.requireEnabled(
                "minirbt_text_classification", "v2"
        );
        assertEquals("MiniRBT 中文文本分类", miniRbt.displayName());
        assertEquals(2, miniRbt.runtimes().size());
        assertEquals(TrainingPlanDefinition.DeviceType.CPU,
                registry.resolveRuntime(miniRbt, "cpu-minirbt-small").runtime().deviceType());
        assertEquals(TrainingPlanDefinition.DeviceType.NVIDIA_GPU,
                registry.resolveRuntime(miniRbt, "gpu-minirbt-small").runtime().deviceType());

        assertTrue(registry.listLatest(false).stream()
                .noneMatch(plan -> "hf_image_classification".equals(plan.id())),
                "the replaced legacy image classification plan must not be offered for new tasks");
    }

    @Test
    void additiveV2FieldsDoNotChangeSerializedV1ApiShape() throws Exception {
        TrainingPlanRegistry registry = new TrainingPlanRegistry(
                new TrainingPlanValidator(new ArtifactSpecRegistry()),
                new TrainingPlanYamlParser()
        );
        registry.initialize();

        String json = new ObjectMapper().writeValueAsString(
                registry.requireEnabled("hf_image_classification", "v1")
        );

        assertTrue(!json.contains("\"category\""));
        assertTrue(!json.contains("\"acceptedSpecIds\""));
        assertTrue(!json.contains("\"publishedModelSpecId\""));
    }

    @Test
    void preparedOnlineSnapshotIsInvisibleUntilSingleAtomicInstall() {
        TrainingPlanValidator validator = new TrainingPlanValidator(new ArtifactSpecRegistry());
        TrainingPlanYamlParser parser = new TrainingPlanYamlParser();
        TrainingPlanRegistry registry = new TrainingPlanRegistry(validator, parser);
        registry.initialize();
        TrainingPlanDefinition online = parser.parse(
                onlineYaml().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "online.yaml"
        );

        TrainingPlanRegistry.PreparedOnlineSnapshot prepared = registry.prepareOnlinePlans(
                java.util.List.of(online)
        );

        assertFalse(registry.find("registry_atomic_plan", "v1").isPresent());
        registry.installOnlinePlans(prepared);
        assertEquals("registry_atomic_plan", registry.requireEnabled("registry_atomic_plan", "v1").id());
    }

    @Test
    void onlineSnapshotCannotOverrideBuiltInIdAndVersion() {
        TrainingPlanRegistry registry = new TrainingPlanRegistry(
                new TrainingPlanValidator(new ArtifactSpecRegistry()),
                new TrainingPlanYamlParser()
        );
        registry.initialize();

        TrainingPlanDefinition builtIn = registry.require("hf_image_classification", "v1");

        assertThrows(IllegalArgumentException.class,
                () -> registry.prepareOnlinePlans(java.util.List.of(builtIn)));
    }

    private String onlineYaml() {
        return """
                schemaVersion: tss.training.plan/v2
                id: registry_atomic_plan
                version: v1
                displayName: Registry atomic fixture
                description: Registry atomic fixture.
                category: CV
                enabled: true
                trainingModes: [FULL_FINETUNE]
                execution:
                  interpreter: python
                  entrypoint: train.py
                  arguments: [--data-dir, "${DATA_DIR}", --output-dir, "${OUTPUT_DIR}"]
                inputs:
                  model:
                    required: true
                    consumed: true
                    acceptedSpecIds: [model.cv.hf-image/v1]
                  dataset:
                    required: true
                    acceptedSpecIds: [dataset.cv.imagefolder/v1]
                  code:
                    required: true
                    approvalRequired: true
                    runtime: PYTHON3
                parameters: []
                runtimes:
                  - id: cpu-small
                    deviceType: CPU
                    image: crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-cv-worker@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
                    imagePullPolicy: IfNotPresent
                    productionDigestRequired: true
                    resourceProfiles:
                      - id: cpu-small
                        cpuRequest: "1"
                        cpuLimit: "2"
                        memoryRequest: 2Gi
                        memoryLimit: 4Gi
                        ephemeralStorageLimit: 8Gi
                        gpuCount: 0
                        nodeSelector:
                          tss.ai/node-pool: cpu
                outputs:
                  progressProtocol: TSS_EVENT_JSONL_V1
                  metricsPath: metrics.json
                  logPath: train.log
                  artifacts:
                    - path: model.zip
                      role: PRIMARY_MODEL
                      required: true
                      format: HF_MODEL_ARCHIVE
                      publishAsModel: true
                      publishedModelSpecId: model.cv.hf-image/v1
                    - path: metrics.json
                      role: METRICS
                      required: true
                      format: JSON
                      publishAsModel: false
                    - path: train.log
                      role: LOG
                      required: true
                      format: TEXT
                      publishAsModel: false
                security:
                  networkPolicy: PLATFORM_SERVICES_ONLY
                  runAsNonRoot: true
                  allowPrivilegeEscalation: false
                  automountServiceAccountToken: false
                  maxRuntimeSeconds: 3600
                """;
    }
}
