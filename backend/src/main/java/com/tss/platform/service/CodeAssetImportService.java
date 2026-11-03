package com.tss.platform.service;

import com.tss.platform.dto.CodeUploadResultDto;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CodeAssetImportService {

    private static final Logger log = LoggerFactory.getLogger(CodeAssetImportService.class);
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final Pattern VERSION_LABEL = Pattern.compile("[A-Za-z0-9._-]+");

    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeValidationRunRepository validationRepository;
    private final CodeZipArchiveService zipArchiveService;
    private final CodeArtifactAssembler assembler;
    private final CodeArtifactStorageService storageService;
    private final MinioDeleteTaskService deleteTaskService;
    private final CodeAssetAuditService auditService;
    private final CodeRiskAssessmentService riskAssessmentService;
    private final TrainingPlanRegistry trainingPlanRegistry;
    private final AuthContext authContext;
    private final TransactionTemplate transactionTemplate;

    public CodeAssetImportService(
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeValidationRunRepository validationRepository,
            CodeZipArchiveService zipArchiveService,
            CodeArtifactAssembler assembler,
            CodeArtifactStorageService storageService,
            MinioDeleteTaskService deleteTaskService,
            CodeAssetAuditService auditService,
            CodeRiskAssessmentService riskAssessmentService,
            TrainingPlanRegistry trainingPlanRegistry,
            AuthContext authContext,
            PlatformTransactionManager transactionManager
    ) {
        this.assetRepository = assetRepository;
        this.versionRepository = versionRepository;
        this.validationRepository = validationRepository;
        this.zipArchiveService = zipArchiveService;
        this.assembler = assembler;
        this.storageService = storageService;
        this.deleteTaskService = deleteTaskService;
        this.auditService = auditService;
        this.riskAssessmentService = riskAssessmentService;
        this.trainingPlanRegistry = trainingPlanRegistry;
        this.authContext = authContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public CodeAssetImportResult importAsset(
            MultipartFile file,
            CodeAssetImportCommand command
    ) {
        ImportOutcome outcome = importInternal(file, command);
        return outcome.safeResult();
    }

    public CodeUploadResultDto importLegacy(
            MultipartFile file,
            String codeName,
            String version,
            String trainingProfile,
            String remark
    ) {
        ImportOutcome outcome = importInternal(file, new CodeAssetImportCommand(
                codeName,
                version,
                trainingProfile,
                null,
                null,
                null,
                null,
                remark
        ));
        CodeAssetImportResult safe = outcome.safeResult();
        return CodeUploadResultDto.builder()
                .codeAssetId(safe.assetId())
                .codeVersionId(safe.versionId())
                .version(safe.version())
                .fileName(safe.fileName())
                .storagePath(outcome.storagePath())
                .sizeBytes(safe.sizeBytes())
                .trainingProfile(safe.trainingProfile())
                .status(safe.status())
                .approvalStatus(safe.approvalStatus())
                .build();
    }

    private ImportOutcome importInternal(
            MultipartFile file,
            CodeAssetImportCommand command
    ) {
        NormalizedImport normalized = normalize(file, command);
        Integer ownerUserId = currentUserId();
        requireUniqueAssetName(ownerUserId, normalized.name(), null);
        byte[] sourceBytes = readArchive(file);
        LinkedHashMap<String, byte[]> files = zipArchiveService.readEntries(
                new ByteArrayInputStream(sourceBytes)
        );
        byte[] deterministicBytes = zipArchiveService.writeDeterministic(files);

        String assetId = "code-asset-" + compactUuid();
        String versionId = "code-version-" + compactUuid();
        CodeAsset assetMetadata = newAsset(
                assetId, ownerUserId, normalized, Instant.now()
        );
        CodeValidationResult validation = assembler.validateOrThrow(
                assetMetadata, deterministicBytes
        );
        String effectiveEntryScript = assembler.effectiveEntryScript(assetMetadata);
        assetMetadata.setEntryScript(effectiveEntryScript);

        String objectName = "users/" + ownerUserId
                + "/codes/" + assetId
                + "/versions/" + versionId
                + "/" + compactUuid() + ".zip";
        boolean uploadAttempted = false;
        try {
            uploadAttempted = true;
            storageService.upload(objectName, deterministicBytes);
            StoredCodeArtifact stored = storageService.read(objectName);
            if (!Arrays.equals(deterministicBytes, stored.bytes())) {
                throw validation(
                        "STORED_ARTIFACT_CHANGED",
                        "Stored code artifact changed after upload"
                );
            }
            CodeValidationResult storedValidation = assembler.validateOrThrow(
                    assetMetadata, stored.bytes()
            );
            if (!storedValidation.passed()
                    || !stored.artifactSha256().equals(
                            storedValidation.artifactSha256())) {
                throw validation(
                        "STORED_ARTIFACT_INVALID",
                        "Stored code artifact validation failed"
                );
            }

            ImportOutcome outcome = transactionTemplate.execute(status -> finalizeImport(
                    assetMetadata,
                    normalized,
                    versionId,
                    objectName,
                    stored,
                    storedValidation
            ));
            if (outcome == null) {
                throw new CodeAssetImportException();
            }
            return outcome;
        } catch (RuntimeException exception) {
            if (uploadAttempted) {
                compensate(objectName, versionId, ownerUserId);
            }
            if (exception instanceof CodeValidationException
                    || exception instanceof CodeArtifactStorageException
                    || exception instanceof CodeAssetImportException
                    || exception instanceof AssetNameConflictException
                    || exception instanceof AssetNameValidationException) {
                throw exception;
            }
            throw new CodeAssetImportException();
        }
    }

    private ImportOutcome finalizeImport(
            CodeAsset asset,
            NormalizedImport normalized,
            String versionId,
            String objectName,
            StoredCodeArtifact stored,
            CodeValidationResult validation
    ) {
        Instant now = Instant.now();
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        saveNewAsset(asset);

        CodeVersion version = new CodeVersion();
        version.setId(versionId);
        version.setAssetId(asset.getId());
        version.setVersion(normalized.version());
        version.setFileName(normalized.fileName());
        version.setStoragePath(objectName);
        version.setSizeBytes(stored.sizeBytes());
        version.setPurpose(asset.getPurpose());
        version.setRuntime(asset.getRuntime());
        version.setEntryScript(asset.getEntryScript());
        version.setTrainingType(asset.getTrainingType());
        version.setTrainingProfile(asset.getTrainingProfile());
        version.setStatus("READY");
        version.setApprovalStatus(CodeApprovalStatus.PENDING);
        version.setArtifactSha256(stored.artifactSha256());
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setOwnerUserId(asset.getOwnerUserId());
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version.setDeleted(false);
        versionRepository.saveAndFlush(version);

        CodeValidationRun run = new CodeValidationRun();
        run.setId("code-validation-" + compactUuid());
        run.setVersionId(versionId);
        run.setArtifactSha256(stored.artifactSha256());
        run.setPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        run.setStatus("PASSED");
        run.setRequestedByUserId(asset.getOwnerUserId());
        run.setCreatedAt(now);
        run.setCompletedAt(now);
        validationRepository.saveAndFlush(run);

        riskAssessmentService.enqueue(
                version.getId(), run.getId(), asset.getOwnerUserId()
        );

        auditService.imported(
                asset.getId(),
                version.getId(),
                validation.fileCount(),
                stored.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION
        );
        CodeAssetImportResult safeResult = new CodeAssetImportResult(
                asset.getId(),
                version.getId(),
                version.getVersion(),
                version.getFileName(),
                stored.sizeBytes(),
                version.getTrainingProfile(),
                version.getStatus(),
                version.getValidationStatus(),
                version.getValidationPolicyVersion(),
                version.getApprovalStatus(),
                version.getArtifactSha256(),
                now
        );
        return new ImportOutcome(safeResult, objectName);
    }

    private static CodeAsset newAsset(
            String assetId,
            Integer ownerUserId,
            NormalizedImport normalized,
            Instant now
    ) {
        CodeAsset asset = new CodeAsset();
        asset.setId(assetId);
        asset.setName(normalized.name());
        asset.setTrainingProfile(normalized.trainingProfile());
        asset.setPurpose(normalized.purpose());
        asset.setRuntime(normalized.runtime());
        asset.setEntryScript(normalized.entryScript());
        asset.setTrainingType(normalized.trainingType());
        asset.setRemark(normalized.remark());
        asset.setOwnerUserId(ownerUserId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);
        return asset;
    }

    private NormalizedImport normalize(
            MultipartFile file,
            CodeAssetImportCommand command
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Code archive file is required");
        }
        if (command == null) {
            throw new IllegalArgumentException("Code import metadata is required");
        }
        String fileName = normalizeZipFileName(file.getOriginalFilename());
        String name = AssetNamePolicy.normalizeRequired(command.name());
        String version = normalizeVersion(command.version());
        String trainingProfile = required(
                command.trainingProfile(), 128, "Training profile is required"
        );
        TrainingPlanDefinition plan = trainingPlanRegistry.requireEnabled(trainingProfile, null);
        String configuredEntrypoint = optional(command.entryScript(), 1024);
        String planEntrypoint = plan.execution().entrypoint();
        return new NormalizedImport(
                name,
                version,
                trainingProfile,
                optional(command.purpose(), 1024),
                optional(command.runtime(), 128),
                configuredEntrypoint == null ? planEntrypoint : configuredEntrypoint,
                optional(command.trainingType(), 128),
                optional(command.remark(), 1024),
                fileName
        );
    }

    private byte[] readArchive(MultipartFile file) {
        try (InputStream input = file.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_ARCHIVE_BYTES - total) {
                    throw validation(
                            "ARCHIVE_TOO_LARGE",
                            "Code archive exceeds the allowed size"
                    );
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (CodeValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Code archive could not be read");
        }
    }

    private Integer currentUserId() {
        Integer userId;
        try {
            userId = authContext.currentUserId();
        } catch (RuntimeException exception) {
            throw new CodeAssetAccessException();
        }
        if (userId == null) {
            throw new CodeAssetAccessException();
        }
        return userId;
    }

    private void requireUniqueAssetName(
            Integer ownerUserId,
            String name,
            String excludedId
    ) {
        if (assetRepository.existsActiveNormalizedName(
                ownerUserId,
                name,
                excludedId
        )) {
            throw new AssetNameConflictException("code");
        }
    }

    private CodeAsset saveNewAsset(CodeAsset asset) {
        try {
            return assetRepository.saveAndFlush(asset);
        } catch (DataIntegrityViolationException exception) {
            if (AssetNamePolicy.isNameConstraintViolation(exception)) {
                throw new AssetNameConflictException("code");
            }
            throw exception;
        }
    }

    private void compensate(String objectName, String versionId, Integer ownerUserId) {
        try {
            deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK,
                    versionId,
                    ownerUserId
            );
        } catch (RuntimeException enqueueFailure) {
            log.error("Code import cleanup enqueue failed: errorType={}",
                    enqueueFailure.getClass().getSimpleName());
            try {
                storageService.delete(objectName);
            } catch (RuntimeException deleteFailure) {
                log.error("Code import fallback cleanup failed: errorType={}",
                        deleteFailure.getClass().getSimpleName());
            }
        }
    }

    private static String normalizeZipFileName(String originalFilename) {
        String value = required(originalFilename, 255, "ZIP file name is required");
        if (value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.chars().anyMatch(Character::isISOControl)
                || !value.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Code archive file name is invalid");
        }
        return value;
    }

    private static String normalizeVersion(String value) {
        String version = value == null || value.isBlank() ? "v1" : value.trim();
        if (version.length() > 64 || !VERSION_LABEL.matcher(version).matches()) {
            throw new IllegalArgumentException("Code version label is invalid");
        }
        return version;
    }

    private static String required(String value, int maxLength, String message) {
        String normalized = optional(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("Code import metadata is too long");
        }
        return normalized;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private record NormalizedImport(
            String name,
            String version,
            String trainingProfile,
            String purpose,
            String runtime,
            String entryScript,
            String trainingType,
            String remark,
            String fileName
    ) {
    }

    private record ImportOutcome(
            CodeAssetImportResult safeResult,
            String storagePath
    ) {
    }
}
