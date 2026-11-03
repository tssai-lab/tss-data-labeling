package com.tss.platform.training.plan;

import com.tss.platform.asset.spec.ArtifactSpecRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanV2ValidationTest {

    private TrainingPlanYamlParser parser;
    private TrainingPlanValidator validator;

    @BeforeEach
    void setUp() {
        ArtifactSpecRegistry registry = new ArtifactSpecRegistry();
        parser = new TrainingPlanYamlParser();
        validator = new TrainingPlanValidator(registry);
    }

    @Test
    void acceptsCpuV2PlanThatReferencesTrainingReadySpecs() {
        TrainingPlanDefinition plan = parse(validYaml());

        validator.validate(plan, "valid-v2.yaml");

        assertEquals(TrainingPlanDefinition.PlanCategory.CV, plan.category());
        assertEquals("model.cv.hf-image/v1", plan.inputs().model().acceptedSpecIds().get(0));
        assertEquals("model.cv.hf-image/v1", plan.outputs().artifacts().get(0).publishedModelSpecId());
    }

    @Test
    void rejectsUnknownStorageOnlyWrongKindAndDuplicateSpecs() {
        assertViolation(replace(validYaml(), "model.cv.hf-image/v1", "model.cv.unknown/v1"),
                "未知资产规范");
        assertViolation(replace(validYaml(), "dataset.cv.imagefolder/v1", "dataset.pointcloud.ply-pcd/v1"),
                "尚未具备训练能力");
        assertViolation(replace(validYaml(), "model.cv.hf-image/v1", "dataset.cv.imagefolder/v1"),
                "资产类型错误");
        assertViolation(replace(validYaml(),
                        "acceptedSpecIds: [dataset.cv.imagefolder/v1]",
                        "acceptedSpecIds: [dataset.cv.imagefolder/v1, dataset.cv.imagefolder/v1]"),
                "不能重复");
    }

    @Test
    void exposesStableCodesAndFieldPathsForPreviewAndManual() {
        TrainingPlanDefinition plan = parse(replace(
                validYaml(), "model.cv.hf-image/v1", "model.cv.unknown/v1"
        ));
        TrainingPlanValidationException error = assertThrows(
                TrainingPlanValidationException.class,
                () -> validator.validate(plan, "coded.yaml")
        );

        assertTrue(error.getDetails().stream().anyMatch(detail ->
                detail.code() == TrainingPlanErrorCode.PLAN_SPEC_UNKNOWN
                        && "inputs.model.acceptedSpecIds".equals(detail.path())
        ));

        TrainingPlanValidationException parseError = assertThrows(
                TrainingPlanValidationException.class,
                () -> parser.parse(new byte[0], "empty.yaml")
        );
        assertEquals(TrainingPlanErrorCode.YAML_EMPTY, parseError.getDetails().get(0).code());
    }

    @Test
    void rejectsLegacyMatchingFieldsAndMissingPublishedSpec() {
        assertViolation(replace(validYaml(),
                        "    acceptedSpecIds: [model.cv.hf-image/v1]",
                        "    acceptedSpecIds: [model.cv.hf-image/v1]\n    taskTypes: []"),
                "禁止 formats/taskTypes/requiredEntries");
        assertViolation(replace(validYaml(),
                        "      publishedModelSpecId: model.cv.hf-image/v1\n",
                        ""),
                "必须声明 publishedModelSpecId");
    }

    @Test
    void acceptsOnlyOneGpuWithPortableNvidiaNodeSelector() {
        String gpuYaml = replace(validYaml(), "deviceType: CPU", "deviceType: NVIDIA_GPU");
        gpuYaml = replace(gpuYaml, "gpuCount: 0", "gpuCount: 1");
        gpuYaml = replace(gpuYaml,
                "tss.ai/node-pool: cpu", "tss.ai/accelerator: nvidia");

        validator.validate(parse(gpuYaml), "valid-gpu-v2.yaml");

        assertViolation(replace(gpuYaml, "gpuCount: 1", "gpuCount: 2"),
                "当前只允许 gpuCount=1");
        assertViolation(replace(gpuYaml,
                        "tss.ai/accelerator: nvidia", "tss.ai/node-pool: gpu"),
                "nodeSelector 必须为 tss.ai/accelerator=nvidia");
    }

    @Test
    void acceptsVersionedMiniRbtSingleGpuAcceptancePlan() throws IOException {
        Path planPath = Path.of(
                "..",
                "examples",
                "acceptance",
                "minirbt_text_classification",
                "minirbt_text_classification_gpu-v1.yaml"
        );
        TrainingPlanDefinition plan = parser.parse(
                Files.readAllBytes(planPath),
                planPath.getFileName().toString()
        );

        validator.validate(plan, planPath.getFileName().toString());

        assertEquals(TrainingPlanDefinition.PlanCategory.NLP, plan.category());
        assertEquals(TrainingPlanDefinition.DeviceType.NVIDIA_GPU,
                plan.runtimes().get(0).deviceType());
        assertEquals(1, plan.runtimes().get(0).resourceProfiles().get(0).gpuCount());
        assertEquals("nvidia",
                plan.runtimes().get(0).resourceProfiles().get(0)
                        .nodeSelector().get("tss.ai/accelerator"));
    }

    @Test
    void acceptsConsolidatedOnlinePlansWithCpuAndGpuResourceProfiles() throws IOException {
        Path root = Path.of("..", "examples", "acceptance", "training_plan_consolidation");
        Path cv = root.resolve("custom_cv_image_classification-v2.yaml");
        Path yolo = root.resolve("yolov11n_object_detection-v11.yaml");

        TrainingPlanDefinition cvPlan = parser.parse(Files.readAllBytes(cv), cv.getFileName().toString());
        TrainingPlanDefinition yoloPlan = parser.parse(Files.readAllBytes(yolo), yolo.getFileName().toString());
        validator.validate(cvPlan, cv.getFileName().toString());
        validator.validate(yoloPlan, yolo.getFileName().toString());

        assertEquals("图像分类", cvPlan.displayName());
        assertEquals(1, cvPlan.runtimes().size());
        assertEquals("YOLO11n 目标检测", yoloPlan.displayName());
        assertEquals(2, yoloPlan.runtimes().size());
        assertEquals(TrainingPlanDefinition.DeviceType.CPU, yoloPlan.runtimes().get(0).deviceType());
        assertEquals(TrainingPlanDefinition.DeviceType.NVIDIA_GPU, yoloPlan.runtimes().get(1).deviceType());
    }

    @Test
    void rejectsUnapprovedMutableImagesAndResourcesOutsideCpuSafetyEnvelope() {
        assertViolation(replace(validYaml(),
                        "crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-cv-worker@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "example.com/worker:latest"),
                "不在平台批准的训练镜像范围内");
        assertViolation(replace(validYaml(), "productionDigestRequired: true", "productionDigestRequired: false"),
                "必须为true");
        assertViolation(replace(validYaml(), "cpuLimit: \"4\"", "cpuLimit: \"9\""),
                "不超过8核");
        assertViolation(replace(validYaml(), "memoryLimit: 8Gi", "memoryLimit: 33Gi"),
                "不超过32Gi");
        assertViolation(replace(validYaml(), "tss.ai/node-pool: cpu", "kubernetes.io/hostname: main"),
                "nodeSelector 只允许");
    }

    @Test
    void rejectsNonFiniteAndStructurallyInvalidParameterDefinitions() {
        String nanParameter = """
                  - name: lr
                    displayName: Learning rate
                    type: NUMBER
                    required: false
                    defaultValue: .nan
                """;
        assertParseFailure(replace(validYaml(), "parameters: []", "parameters:\n" + nanParameter));

        String invalidBoolean = """
                  - name: enabledFlag
                    displayName: Enabled
                    type: BOOLEAN
                    required: false
                    minimum: 0
                    allowedValues: [true, true]
                """;
        String invalidYaml = replace(validYaml(), "parameters: []", "parameters:\n" + invalidBoolean);
        assertViolation(invalidYaml, "不能声明 minimum/maximum");
        assertViolation(invalidYaml, "allowedValues 不能重复");
    }

    @Test
    void hardenedParserRejectsUnknownFieldsDuplicateKeysAliasesBomInvalidUtf8AndMultipleDocuments() {
        assertParseFailure(replace(validYaml(), "category: CV", "category: CV\nunknownField: value"));
        assertParseFailure(replace(validYaml(), "category: CV", "category: CV\ncategory: NLP"));
        TrainingPlanValidationException alias = parseFailure(replace(validYaml(),
                "parameters: []",
                "parameters: &shared []\nruntimes: *shared"));
        assertEquals(TrainingPlanErrorCode.YAML_ALIAS_NOT_ALLOWED, alias.getDetails().get(0).code());
        assertParseFailure(replace(validYaml(),
                "description: Minimal v2 validation fixture.",
                "description: &text fixture\nunavailableReason: *text"));
        TrainingPlanValidationException tag = parseFailure(replace(validYaml(),
                "description: Minimal v2 validation fixture.",
                "description: !!str fixture"));
        assertEquals(TrainingPlanErrorCode.YAML_TAG_NOT_ALLOWED, tag.getDetails().get(0).code());
        assertParseFailure("\uFEFF" + validYaml());
        assertThrows(TrainingPlanValidationException.class,
                () -> parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}, "invalid.yaml"));
        TrainingPlanValidationException multiple = assertThrows(
                TrainingPlanValidationException.class,
                () -> parse(validYaml() + "\n---\n" + validYaml())
        );
        assertEquals(TrainingPlanErrorCode.YAML_MULTIPLE_DOCUMENTS,
                multiple.getDetails().get(0).code());
    }

    @Test
    void hardenedParserRejectsEmptyAndOversizedDocuments() {
        assertParseFailure("   \n");
        assertThrows(TrainingPlanValidationException.class,
                () -> parser.parse(new byte[TrainingPlanYamlParser.MAX_BYTES + 1], "large.yaml"));
    }

    private TrainingPlanDefinition parse(String yaml) {
        return parser.parse(yaml.getBytes(StandardCharsets.UTF_8), "test-v2.yaml");
    }

    private void assertViolation(String yaml, String expected) {
        TrainingPlanDefinition plan = parse(yaml);
        TrainingPlanValidationException error = assertThrows(
                TrainingPlanValidationException.class,
                () -> validator.validate(plan, "test-v2.yaml")
        );
        assertTrue(error.getViolations().stream().anyMatch(item -> item.contains(expected)),
                () -> "Expected violation containing '" + expected + "' but got " + error.getViolations());
    }

    private void assertParseFailure(String yaml) {
        parseFailure(yaml);
    }

    private TrainingPlanValidationException parseFailure(String yaml) {
        return assertThrows(TrainingPlanValidationException.class,
                () -> parser.parse(yaml.getBytes(StandardCharsets.UTF_8), "invalid.yaml"));
    }

    private String replace(String source, String before, String after) {
        String result = source.replace(before, after);
        if (result.equals(source)) {
            throw new IllegalStateException("test fixture replacement did not match: " + before);
        }
        return result;
    }

    private String validYaml() {
        return """
                schemaVersion: tss.training.plan/v2
                id: hf_image_classification_custom
                version: v2
                displayName: Custom CPU image classification
                description: Minimal v2 validation fixture.
                category: CV
                enabled: true
                trainingModes: [FULL_FINETUNE]

                execution:
                  interpreter: python
                  entrypoint: train.py
                  arguments: [--model-dir, "${MODEL_DIR}", --data-dir, "${DATA_DIR}", --output-dir, "${OUTPUT_DIR}"]

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
                        cpuLimit: "4"
                        memoryRequest: 4Gi
                        memoryLimit: 8Gi
                        ephemeralStorageLimit: 12Gi
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
                  maxRuntimeSeconds: 28800
                """;
    }
}
