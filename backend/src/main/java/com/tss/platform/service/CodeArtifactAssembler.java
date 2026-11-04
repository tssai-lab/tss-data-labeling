package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CodeArtifactAssembler {

    public static final String POLICY_VERSION = "CODE_ASSET_POLICY_V1";

    private final CodeArtifactStorageService storageService;
    private final CodeZipArchiveService zipArchiveService;
    private final CodePathPolicy pathPolicy;
    private final CodeFilePolicy filePolicy;
    private final PythonRequirementsValidator requirementsValidator;
    private final TrainingPlanRegistry trainingPlanRegistry;

    public CodeArtifactAssembler(
            CodeArtifactStorageService storageService,
            CodeZipArchiveService zipArchiveService,
            CodePathPolicy pathPolicy,
            CodeFilePolicy filePolicy,
            PythonRequirementsValidator requirementsValidator,
            TrainingPlanRegistry trainingPlanRegistry
    ) {
        this.storageService = storageService;
        this.zipArchiveService = zipArchiveService;
        this.pathPolicy = pathPolicy;
        this.filePolicy = filePolicy;
        this.requirementsValidator = requirementsValidator;
        this.trainingPlanRegistry = trainingPlanRegistry;
    }

    /** Keeps the existing Spring wiring contract while still applying requirements validation. */
    @Autowired
    public CodeArtifactAssembler(
            CodeArtifactStorageService storageService,
            CodeZipArchiveService zipArchiveService,
            CodePathPolicy pathPolicy,
            CodeFilePolicy filePolicy,
            TrainingPlanRegistry trainingPlanRegistry
    ) {
        this(storageService, zipArchiveService, pathPolicy, filePolicy, new PythonRequirementsValidator(), trainingPlanRegistry);
    }

    public MaterializedCodeArtifact materialize(
            CodeAsset asset,
            CodeVersion baseVersion,
            List<CodeWorkspaceFileDelta> deltas
    ) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        if (baseVersion != null) {
            StoredCodeArtifact stored = storageService.read(baseVersion.getStoragePath());
            if (!stored.artifactSha256().equals(baseVersion.getArtifactSha256())) {
                throw validation(
                        "BASE_ARTIFACT_HASH_MISMATCH",
                        "Base code artifact evidence does not match"
                );
            }
            files.putAll(zipArchiveService.readEntries(new ByteArrayInputStream(stored.bytes())));
        }
        if (deltas != null) {
            deltas.stream()
                    .sorted(java.util.Comparator.comparing(CodeWorkspaceFileDelta::getPath))
                    .forEach(delta -> applyDelta(files, delta));
        }
        byte[] bytes = zipArchiveService.writeDeterministic(files);
        CodeValidationResult result = validate(asset, bytes);
        return new MaterializedCodeArtifact(bytes, files, result);
    }

    public CodeValidationResult validate(CodeAsset asset, byte[] archiveBytes) {
        String sha = filePolicy.sha256(archiveBytes);
        try {
            return validateOrThrow(asset, archiveBytes);
        } catch (CodeValidationException exception) {
            return new CodeValidationResult(
                    POLICY_VERSION,
                    sha,
                    "FAILED",
                    exception.getReasonCode(),
                    "Code artifact validation failed",
                    0
            );
        }
    }

    public CodeValidationResult validateOrThrow(CodeAsset asset, byte[] archiveBytes) {
        if (asset == null || archiveBytes == null) {
            throw validation("INVALID_ARTIFACT", "Code artifact is invalid");
        }
        LinkedHashMap<String, byte[]> files = zipArchiveService.readEntries(
                new ByteArrayInputStream(archiveBytes)
        );
        String entryScript = effectiveEntryScript(asset);
        if (!files.containsKey(entryScript)) {
            throw validation("ENTRY_SCRIPT_MISSING", "Code artifact entry script is missing");
        }
        requirementsValidator.parse(files.get("requirements.txt"));
        return new CodeValidationResult(
                POLICY_VERSION,
                filePolicy.sha256(archiveBytes),
                "PASSED",
                null,
                "Code artifact validation passed",
                files.size()
        );
    }

    private void applyDelta(Map<String, byte[]> files, CodeWorkspaceFileDelta delta) {
        String path = pathPolicy.normalizeFilePath(delta.getPath());
        if (CodeWorkspaceFileDelta.OPERATION_DELETE.equals(delta.getOperation())) {
            if (delta.getContentBytes() != null
                    || delta.getContentHash() != null
                    || delta.getSizeBytes() != null) {
                throw validation("INVALID_DELTA", "Code workspace delta is invalid");
            }
            files.remove(path);
            return;
        }
        if (!CodeWorkspaceFileDelta.OPERATION_UPSERT.equals(delta.getOperation())) {
            throw validation("INVALID_DELTA", "Code workspace delta is invalid");
        }
        byte[] bytes = delta.getContentBytes();
        if (bytes == null
                || delta.getSizeBytes() == null
                || delta.getSizeBytes() != bytes.length
                || delta.getContentHash() == null
                || !delta.getContentHash().equals(filePolicy.sha256(bytes))) {
            throw validation("INVALID_DELTA", "Code workspace delta is invalid");
        }
        filePolicy.validateSupportedPath(path);
        filePolicy.validateUtf8(bytes);
        files.put(path, Arrays.copyOf(bytes, bytes.length));
    }

    public String effectiveEntryScript(CodeAsset asset) {
        String configured = trimToNull(asset.getEntryScript());
        if (configured == null) {
            String profile = trimToNull(asset.getTrainingProfile());
            configured = profile == null
                    ? null
                    : trainingPlanRegistry.find(profile, null)
                            .map(TrainingPlanDefinition::execution)
                            .map(TrainingPlanDefinition.Execution::entrypoint)
                            .orElse(null);
        }
        if (configured == null) {
            throw validation("ENTRY_SCRIPT_REQUIRED", "Code artifact entry script is required");
        }
        try {
            return pathPolicy.normalizeFilePath(configured);
        } catch (CodeValidationException exception) {
            throw validation("ENTRY_SCRIPT_INVALID", "Code artifact entry script is invalid");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }
}
