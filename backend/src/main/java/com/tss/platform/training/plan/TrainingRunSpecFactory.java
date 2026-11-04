package com.tss.platform.training.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.model.TaskType;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ArtifactDigestService;
import com.tss.platform.service.CodeDependencyManifestService;
import com.tss.platform.service.PythonRequirementsValidator;
import com.tss.platform.service.ResolvedCodeArtifact;
import com.tss.platform.service.TrainingHardwareOptionService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class TrainingRunSpecFactory {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final TrainingPlanRegistry planRegistry;
    private final TrainingPlanValidator planValidator;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelAssetRepository modelAssetRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final DatasetAssetRepository datasetAssetRepository;
    private final CodeVersionRepository codeVersionRepository;
    private final ArtifactDigestService digestService;
    private final CodeDependencyManifestService dependencyManifestService;
    private final AuthContext authContext;
    private final TrainingHardwareOptionService hardwareOptionService;
    private final ObjectMapper snapshotMapper;

    public TrainingRunSpecFactory(
            TrainingPlanRegistry planRegistry,
            TrainingPlanValidator planValidator,
            ModelVersionRepository modelVersionRepository,
            ModelAssetRepository modelAssetRepository,
            DatasetVersionRepository datasetVersionRepository,
            DatasetAssetRepository datasetAssetRepository,
            CodeVersionRepository codeVersionRepository,
            ArtifactDigestService digestService,
            CodeDependencyManifestService dependencyManifestService,
            AuthContext authContext,
            TrainingHardwareOptionService hardwareOptionService,
            ObjectMapper objectMapper
    ) {
        this.planRegistry = planRegistry;
        this.planValidator = planValidator;
        this.modelVersionRepository = modelVersionRepository;
        this.modelAssetRepository = modelAssetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.datasetAssetRepository = datasetAssetRepository;
        this.codeVersionRepository = codeVersionRepository;
        this.digestService = digestService;
        this.dependencyManifestService = dependencyManifestService;
        this.authContext = authContext;
        this.hardwareOptionService = hardwareOptionService;
        this.snapshotMapper = objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    public TrainingRunSnapshot create(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.trainingId(), "trainingId cannot be empty");
        requireText(command.planId(), "planId cannot be empty");
        requireText(command.modelVersionId(), "modelVersionId cannot be empty");
        requireText(command.datasetVersionId(), "datasetVersionId cannot be empty");
        requireText(command.codeVersionId(), "codeVersionId cannot be empty");
        if (command.codeArtifact() == null
                || !command.codeVersionId().equals(command.codeArtifact().versionId())) {
            throw new IllegalArgumentException("approved code artifact does not match codeVersionId");
        }

        TrainingPlanDefinition plan = planRegistry.requireEnabled(command.planId(), command.planVersion());
        TrainingPlanDefinition.TrainingMode trainingMode = resolveTrainingMode(plan, command.trainingMode());
        String resourceProfileId = resolveResourceProfileId(plan, command.resourceProfileId(), command.legacySelection());
        TrainingPlanRegistry.ResolvedRuntime resolvedRuntime = planRegistry.resolveRuntime(plan, resourceProfileId);
        Map<String, Object> parameters = planValidator.resolveParameters(plan, command.parameters());

        ModelVersion modelVersion = modelVersionRepository
                .findByIdAndDeletedFalse(command.modelVersionId())
                .orElseThrow(() -> new IllegalArgumentException("model version does not exist"));
        ModelAsset modelAsset = modelAssetRepository.findByIdAndDeletedFalse(modelVersion.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("model asset does not exist"));
        requireOwner(modelVersion.getOwnerUserId(), modelAsset.getOwnerUserId(), "model version");
        requireReadyArtifact(modelVersion.getStatus(), modelVersion.getStoragePath(), modelVersion.getSizeBytes(), "model");
        String modelSpecId = resolveAcceptedSpec(
                plan.inputs().model().acceptedSpecIds(),
                modelVersion.getArtifactSpecId(),
                "model"
        );
        if (modelSpecId == null) {
            requireTaskType(plan.inputs().model().taskTypes(), modelAsset.getType(), "model");
        }

        DatasetVersion datasetVersion = datasetVersionRepository
                .findByIdAndDeletedFalse(command.datasetVersionId())
                .orElseThrow(() -> new IllegalArgumentException("dataset version does not exist"));
        DatasetAsset datasetAsset = datasetAssetRepository.findByIdAndDeletedFalse(datasetVersion.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("dataset asset does not exist"));
        requireOwner(datasetVersion.getOwnerUserId(), datasetAsset.getOwnerUserId(), "dataset version");
        requireReadyArtifact(datasetVersion.getStatus(), datasetVersion.getStoragePath(), datasetVersion.getSizeBytes(), "dataset");
        String datasetSpecId = resolveAcceptedSpec(
                plan.inputs().dataset().acceptedSpecIds(),
                datasetVersion.getArtifactSpecId(),
                "dataset"
        );
        if (datasetSpecId == null) {
            requireTaskType(plan.inputs().dataset().taskTypes(), datasetAsset.getType(), "dataset");
            requireAllowed(plan.inputs().dataset().cvTaskTypes(),
                    firstText(datasetVersion.getCvTaskType(), datasetAsset.getCvTaskType()), "dataset cvTaskType");
            requireAllowed(plan.inputs().dataset().annotationFormats(),
                    firstText(datasetVersion.getAnnotationFormat(), datasetAsset.getAnnotationFormat()),
                    "dataset annotationFormat");
        }

        CodeVersion codeVersion = codeVersionRepository.findByIdAndDeletedFalse(command.codeVersionId())
                .orElseThrow(() -> new IllegalArgumentException("code version does not exist"));
        requireReadyArtifact(codeVersion.getStatus(), command.codeArtifact().storagePath(), codeVersion.getSizeBytes(), "code");
        if (!plan.id().equals(command.codeArtifact().trainingProfile())) {
            throw new IllegalArgumentException("code trainingProfile does not match selected plan");
        }
        if (!plan.execution().entrypoint().equals(command.codeArtifact().entryScript())) {
            throw new IllegalArgumentException("approved code entrypoint does not match selected plan");
        }

        String modelSha256 = resolveModelDigest(modelVersion);
        String datasetSha256 = resolveDatasetDigest(datasetVersion);
        requireSha256(command.codeArtifact().artifactSha256(), "code artifact SHA-256");

        PythonRequirementsValidator.DependencyManifest dependencies = dependencyManifestService.resolve(command.codeArtifact());
        TrainingRunSpec.Runtime runtime = runtimeOf(resolvedRuntime.runtime(), dependencies.sha256());
        TrainingHardwareOptionService.HardwareSelection hardwareSelection =
                hardwareOptionService.requireSelection(
                        plan, resolvedRuntime.runtime(), resolvedRuntime.resourceProfile(),
                        command.resourceRequest());
        TrainingRunSpec.Resources resources = TrainingResourceRequestResolver.resolve(
                resolvedRuntime.runtime(), resolvedRuntime.resourceProfile(), command.resourceRequest(),
                hardwareSelection.hardwareTargetId(), hardwareSelection.nodeSelector(),
                hardwareSelection.gpuNodeName(), hardwareSelection.gpuHostIndex(),
                hardwareSelection.gpuUuid(), hardwareSelection.gpuModel(),
                hardwareSelection.gpuTotalMemoryMiB());
        TrainingRunSpec.Workspace workspace = TrainingRunSpec.Workspace.standard();
        TrainingRunSpec runSpec = new TrainingRunSpec(
                TrainingRunSpec.SCHEMA_VERSION,
                command.trainingId(),
                command.createdAt() == null ? Instant.now() : command.createdAt(),
                new TrainingRunSpec.PlanRef(plan.id(), plan.version()),
                trainingMode,
                new TrainingRunSpec.Inputs(
                        new TrainingRunSpec.InputArtifact(
                                modelVersion.getId(), modelVersion.getStoragePath(), modelSha256,
                                modelVersion.getSizeBytes(), resolveModelFormat(plan, modelVersion.getFileName(), modelSpecId),
                                materializedFileName(modelVersion.getFileName(), plan.inputs().model().requiredEntries()),
                                isArchive(modelVersion.getFileName()),
                                safeEntries(plan.inputs().model().requiredEntries()),
                                modelSpecId
                        ),
                        new TrainingRunSpec.InputArtifact(
                                datasetVersion.getId(), datasetVersion.getStoragePath(), datasetSha256,
                                datasetVersion.getSizeBytes(), resolveDatasetFormat(datasetVersion, datasetAsset, datasetSpecId),
                                materializedFileName(datasetVersion.getFileName(), plan.inputs().dataset().requiredEntries()),
                                isArchive(datasetVersion.getFileName()),
                                safeEntries(plan.inputs().dataset().requiredEntries()),
                                datasetSpecId
                        ),
                        new TrainingRunSpec.CodeArtifact(
                                codeVersion.getId(), command.codeArtifact().storagePath(),
                                command.codeArtifact().artifactSha256(), codeVersion.getSizeBytes(), "ZIP",
                                materializedFileName(codeVersion.getFileName(), List.of()),
                                true,
                                List.of(plan.execution().entrypoint()),
                                plan.execution().entrypoint(), command.codeArtifact().approvalRecordId(),
                                dependencies.requirements(), dependencies.sha256()
                        )
                ),
                new TrainingRunSpec.Execution(
                        resolveArgv(plan, runtime.deviceType(), workspace),
                        workspace.codeDir()
                ),
                parameters,
                runtime,
                resources,
                workspace,
                plan.outputs(),
                plan.security()
        );

        String json = writeSnapshot(runSpec);
        String runSpecSha256 = sha256(json.getBytes(StandardCharsets.UTF_8));
        return new TrainingRunSnapshot(
                runSpec,
                json,
                runSpecSha256,
                parameters,
                modelSha256,
                datasetSha256,
                command.codeArtifact().artifactSha256(),
                command.codeArtifact().approvalRecordId(),
                runtime.image(),
                runtime.imageDigest()
        );
    }

    private TrainingPlanDefinition.TrainingMode resolveTrainingMode(
            TrainingPlanDefinition plan,
            String requested
    ) {
        List<TrainingPlanDefinition.TrainingMode> supported = plan.trainingModes() == null
                ? List.of()
                : plan.trainingModes();
        if (supported.isEmpty()) {
            throw new IllegalArgumentException("training plan has no supported training mode");
        }
        TrainingPlanDefinition.TrainingMode resolved;
        if (requested == null || requested.isBlank()) {
            if (supported.size() != 1) {
                throw new IllegalArgumentException("trainingMode is required for selected plan");
            }
            resolved = supported.get(0);
        } else {
            try {
                resolved = TrainingPlanDefinition.TrainingMode.valueOf(
                        requested.trim().toUpperCase(Locale.ROOT)
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("trainingMode is invalid");
            }
        }
        if (!supported.contains(resolved)) {
            throw new IllegalArgumentException("trainingMode is not supported by selected plan");
        }
        return resolved;
    }

    private String resolveResourceProfileId(
            TrainingPlanDefinition plan,
            String requested,
            boolean legacySelection
    ) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (!legacySelection) {
            throw new IllegalArgumentException("resourceProfileId is required when planId is selected");
        }
        return plan.runtimes().stream()
                .flatMap(runtime -> runtime.resourceProfiles().stream())
                .map(TrainingPlanDefinition.ResourceProfile::id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("training plan has no resource profile"));
    }

    private String resolveModelDigest(ModelVersion version) {
        if (validSha256(version.getArtifactSha256())) {
            return version.getArtifactSha256();
        }
        String sha256 = digestService.digest(version.getStoragePath(), version.getSizeBytes()).sha256();
        version.setArtifactSha256(sha256);
        modelVersionRepository.save(version);
        return sha256;
    }

    private String resolveDatasetDigest(DatasetVersion version) {
        if (validSha256(version.getArtifactSha256())) {
            return version.getArtifactSha256();
        }
        String sha256 = digestService.digest(version.getStoragePath(), version.getSizeBytes()).sha256();
        version.setArtifactSha256(sha256);
        datasetVersionRepository.save(version);
        return sha256;
    }

    private List<String> resolveArgv(
            TrainingPlanDefinition plan,
            TrainingPlanDefinition.DeviceType deviceType,
            TrainingRunSpec.Workspace workspace
    ) {
        Map<String, String> placeholders = Map.of(
                "${MODEL_DIR}", workspace.modelDir(),
                "${DATA_DIR}", workspace.dataDir(),
                "${CODE_DIR}", workspace.codeDir(),
                "${OUTPUT_DIR}", workspace.outputDir(),
                "${PARAMS_FILE}", workspace.paramsFile(),
                "${DEVICE}", deviceType == TrainingPlanDefinition.DeviceType.NVIDIA_GPU ? "0" : "cpu"
        );
        List<String> argv = new ArrayList<>();
        argv.add(plan.execution().interpreter());
        argv.add(workspace.codeDir() + "/" + plan.execution().entrypoint());
        for (String raw : plan.execution().arguments()) {
            String resolved = raw;
            for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
                resolved = resolved.replace(placeholder.getKey(), placeholder.getValue());
            }
            if (resolved.contains("${")) {
                throw new IllegalArgumentException("execution argument contains unresolved placeholder");
            }
            argv.add(resolved);
        }
        return List.copyOf(argv);
    }

    private TrainingRunSpec.Runtime runtimeOf(
            TrainingPlanDefinition.RuntimeVariant runtime,
            String requirementsSha256
    ) {
        String image = runtime.image();
        String imageDigest = null;
        int digestSeparator = image.indexOf("@sha256:");
        if (digestSeparator >= 0) {
            imageDigest = image.substring(digestSeparator + 1);
            image = image.substring(0, digestSeparator);
        }
        return new TrainingRunSpec.Runtime(
                runtime.id(), runtime.deviceType(), image, imageDigest, runtime.imagePullPolicy(),
                requirementsSha256 == null ? null : sha256((image + "@" + imageDigest + "\n" + requirementsSha256)
                        .getBytes(StandardCharsets.UTF_8))
        );
    }

    private String resolveModelFormat(TrainingPlanDefinition plan, String fileName, String artifactSpecId) {
        if (artifactSpecId != null) {
            return artifactSpecId;
        }
        List<String> allowed = plan.inputs().model().formats();
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String inferred = normalized.endsWith(".pt") ? "YOLO_PT"
                : normalized.endsWith(".zip") ? "LEGACY_WEIGHT_ARCHIVE"
                : null;
        if (inferred != null && allowed.contains(inferred)) {
            return inferred;
        }
        if (allowed.size() == 1) {
            return allowed.get(0);
        }
        throw new IllegalArgumentException("model file format is not supported by selected plan");
    }

    private String resolveDatasetFormat(
            DatasetVersion version,
            DatasetAsset asset,
            String artifactSpecId
    ) {
        if (artifactSpecId != null) {
            return artifactSpecId;
        }
        String annotation = firstText(version.getAnnotationFormat(), asset.getAnnotationFormat());
        return annotation == null ? "DATASET_ARCHIVE" : annotation.trim().toUpperCase(Locale.ROOT);
    }

    static String resolveAcceptedSpec(List<String> acceptedSpecIds, String actualSpecId, String field) {
        if (acceptedSpecIds == null) {
            return null;
        }
        if (acceptedSpecIds.isEmpty()) {
            throw new IllegalArgumentException(field + " acceptedSpecIds cannot be empty");
        }
        if (actualSpecId == null || actualSpecId.isBlank()) {
            throw new IllegalArgumentException(field + " version has no verified artifact specification");
        }
        String normalized = actualSpecId.trim();
        if (acceptedSpecIds.stream().noneMatch(normalized::equals)) {
            throw new IllegalArgumentException(field + " artifact specification is incompatible with selected plan");
        }
        return normalized;
    }

    private boolean isArchive(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private String materializedFileName(String sourceFileName, List<String> requiredEntries) {
        if (!isArchive(sourceFileName) && requiredEntries != null && requiredEntries.size() == 1) {
            return requiredEntries.get(0);
        }
        String normalized = sourceFileName == null ? "artifact.bin" : sourceFileName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String leaf = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (leaf.isBlank() || ".".equals(leaf) || "..".equals(leaf)) {
            throw new IllegalArgumentException("artifact fileName is invalid");
        }
        return leaf;
    }

    private List<String> safeEntries(List<String> entries) {
        return entries == null ? List.of() : List.copyOf(entries);
    }

    private void requireTaskType(List<String> allowed, String actual, String field) {
        String normalized = TaskType.normalize(actual);
        if (allowed == null || allowed.stream().noneMatch(item -> normalized.equals(TaskType.normalize(item)))) {
            throw new IllegalArgumentException(field + " task type is incompatible with selected plan");
        }
    }

    private void requireAllowed(List<String> allowed, String actual, String field) {
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        if (actual == null || allowed.stream().noneMatch(item -> item.equalsIgnoreCase(actual.trim()))) {
            throw new IllegalArgumentException(field + " is incompatible with selected plan");
        }
    }

    private void requireOwner(Integer versionOwner, Integer assetOwner, String field) {
        Integer owner = versionOwner != null ? versionOwner : assetOwner;
        authContext.requireOwnerAccess(owner, field + " does not exist or is not accessible");
    }

    private void requireReadyArtifact(String status, String storagePath, Long sizeBytes, String field) {
        if (!"READY".equals(status)) {
            throw new IllegalArgumentException(field + " version must be READY");
        }
        requireText(storagePath, field + " storage path is required");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new IllegalArgumentException(field + " sizeBytes must be greater than zero");
        }
    }

    private String writeSnapshot(TrainingRunSpec runSpec) {
        try {
            return snapshotMapper.writeValueAsString(runSpec);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize training run spec", e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void requireSha256(String value, String field) {
        if (!validSha256(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private boolean validSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    public record CreateCommand(
            String trainingId,
            Instant createdAt,
            String planId,
            String planVersion,
            String trainingMode,
            String resourceProfileId,
            boolean legacySelection,
            String modelVersionId,
            String datasetVersionId,
            String codeVersionId,
            Map<String, ?> parameters,
            ResolvedCodeArtifact codeArtifact,
            com.tss.platform.dto.TrainingResourceRequest resourceRequest
    ) {
        public CreateCommand(
                String trainingId,
                Instant createdAt,
                String planId,
                String planVersion,
                String resourceProfileId,
                boolean legacySelection,
                String modelVersionId,
                String datasetVersionId,
                String codeVersionId,
                Map<String, ?> parameters,
                ResolvedCodeArtifact codeArtifact
        ) {
            this(trainingId, createdAt, planId, planVersion, null, resourceProfileId,
                    legacySelection, modelVersionId, datasetVersionId, codeVersionId,
                    parameters, codeArtifact, null);
        }
    }
}
