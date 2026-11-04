package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeArtifactUpgradeResult;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit administrator workflow for moving legacy code artifacts into the
 * immutable per-version namespace before creating fresh validation evidence.
 */
@Service
public class CodeArtifactUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(CodeArtifactUpgradeService.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeArtifactStorageService storageService;
    private final MinioDeleteTaskService deleteTaskService;
    private final CodeAssetAuditService auditService;
    private final CodeApprovalService approvalService;
    private final CodeValidationService validationService;
    private final CodeFilePolicy filePolicy;
    private final TransactionTemplate transactionTemplate;

    public CodeArtifactUpgradeService(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeArtifactStorageService storageService,
            MinioDeleteTaskService deleteTaskService,
            CodeAssetAuditService auditService,
            CodeApprovalService approvalService,
            CodeValidationService validationService,
            CodeFilePolicy filePolicy,
            PlatformTransactionManager transactionManager
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.storageService = storageService;
        this.deleteTaskService = deleteTaskService;
        this.auditService = auditService;
        this.approvalService = approvalService;
        this.validationService = validationService;
        this.filePolicy = filePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public V2CodeArtifactUpgradeResult upgrade(String versionId) {
        approvalService.requireAdministratorAuthority();
        UpgradeSnapshot snapshot = snapshot(versionId);
        PathKind pathKind = classifyPath(snapshot);
        if (pathKind == PathKind.CANONICAL) {
            StoredCodeArtifact stored = readAndVerify(snapshot.storagePath());
            requireCanonicalEvidence(snapshot, stored);
            confirmCanonicalBeforeValidation(snapshot, stored);
            return validate(snapshot, stored, false);
        }

        StoredCodeArtifact source = readAndVerify(snapshot.storagePath());
        String destination = canonicalObjectName(snapshot);
        boolean uploadAttempted = false;
        StoredCodeArtifact finalized;
        try {
            uploadAttempted = true;
            storageService.upload(destination, source.bytes());
            StoredCodeArtifact copied = readAndVerify(destination);
            requireExactCopy(source, copied, destination);
            finalized = transactionTemplate.execute(status ->
                    finalizeUpgrade(snapshot, copied)
            );
            if (finalized == null) {
                throw conflict(
                        "ARTIFACT_UPGRADE_TRANSACTION_FAILED",
                        "Code artifact upgrade transaction did not complete"
                );
            }
        } catch (RuntimeException exception) {
            if (uploadAttempted) {
                compensate(destination, snapshot.versionId(), snapshot.ownerUserId());
            }
            throw exception;
        }
        return validate(snapshot, finalized, true);
    }

    private UpgradeSnapshot snapshot(String versionId) {
        CodeVersion version = versionRepository.findByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        requireIdentity(asset, version);
        if (!CodeApprovalStatus.PENDING.equals(version.getApprovalStatus())) {
            throw conflict(
                    "ARTIFACT_UPGRADE_APPROVAL_CONFLICT",
                    "Only pending code versions can be upgraded"
            );
        }
        return new UpgradeSnapshot(
                version.getId(),
                asset.getId(),
                asset.getOwnerUserId(),
                asset.getRowVersion(),
                version.getVersion(),
                version.getFileName(),
                version.getStoragePath(),
                version.getSizeBytes(),
                version.getStatus(),
                version.getApprovalStatus(),
                version.getArtifactSha256(),
                version.getValidationStatus(),
                version.getValidationPolicyVersion(),
                version.getPurpose(),
                version.getRuntime(),
                version.getEntryScript(),
                version.getTrainingType(),
                version.getTrainingProfile(),
                version.getUpdatedAt()
        );
    }

    private StoredCodeArtifact finalizeUpgrade(
            UpgradeSnapshot snapshot,
            StoredCodeArtifact copied
    ) {
        CodeAsset asset = assetRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.assetId())
                .orElseThrow(CodeAssetAccessException::new);
        CodeVersion version = versionRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.versionId())
                .orElseThrow(CodeAssetAccessException::new);
        requireIdentity(asset, version);
        if (!sameSnapshot(asset, version, snapshot)) {
            throw conflict(
                    "ARTIFACT_UPGRADE_CONFLICT",
                    "Code version changed during artifact upgrade"
            );
        }

        version.setStoragePath(copied.objectName());
        version.setArtifactSha256(copied.artifactSha256());
        version.setSizeBytes(copied.sizeBytes());
        version.setValidationStatus("NOT_RUN");
        version.setValidationPolicyVersion(null);
        version.setApprovalStatus(CodeApprovalStatus.PENDING);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.artifactUpgraded(
                asset.getId(), version.getId(), copied.artifactSha256()
        );
        deleteTaskService.enqueueDefaultBucketDelete(
                snapshot.storagePath(),
                MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_UPGRADE,
                version.getId(),
                snapshot.ownerUserId()
        );
        return copied;
    }

    private V2CodeArtifactUpgradeResult validate(
            UpgradeSnapshot snapshot,
            StoredCodeArtifact stored,
            boolean upgraded
    ) {
        CodeValidationResult validation = validationService.validateVersion(snapshot.versionId());
        if (!validation.passed()) {
            if ("STORAGE_READ_FAILED".equals(validation.reasonCode())) {
                throw new CodeArtifactStorageException();
            }
            throw validation(
                    safeReason(validation.reasonCode()),
                    "Code artifact validation failed"
            );
        }
        ConfirmedEvidence confirmed = transactionTemplate.execute(status ->
                confirmCurrentEvidence(snapshot, stored)
        );
        if (confirmed == null) {
            throw conflict(
                    "ARTIFACT_UPGRADE_CONFIRMATION_FAILED",
                    "Code artifact upgrade confirmation did not complete"
            );
        }
        return new V2CodeArtifactUpgradeResult(
                confirmed.versionId(),
                confirmed.artifactSha256(),
                confirmed.sizeBytes(),
                confirmed.approvalStatus(),
                upgraded,
                V2CodeValidationResult.from(validation)
        );
    }

    private void confirmCanonicalBeforeValidation(
            UpgradeSnapshot snapshot,
            StoredCodeArtifact stored
    ) {
        Boolean confirmed = transactionTemplate.execute(status -> {
            LockedVersion locked = lockVersion(snapshot);
            if (!sameSnapshot(locked.asset(), locked.version(), snapshot)
                    || !sameStoredEvidence(locked.version(), stored)) {
                throw conflict(
                        "ARTIFACT_UPGRADE_CONFLICT",
                        "Code version changed before artifact validation"
                );
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(confirmed)) {
            throw conflict(
                    "ARTIFACT_UPGRADE_CONFIRMATION_FAILED",
                    "Code artifact upgrade confirmation did not complete"
            );
        }
    }

    private ConfirmedEvidence confirmCurrentEvidence(
            UpgradeSnapshot snapshot,
            StoredCodeArtifact stored
    ) {
        LockedVersion locked = lockVersion(snapshot);
        CodeVersion version = locked.version();
        if (!sameFrozenMetadata(locked.asset(), version, snapshot)
                || !sameStoredEvidence(version, stored)
                || !CodeApprovalStatus.PENDING.equals(version.getApprovalStatus())) {
            throw conflict(
                    "ARTIFACT_UPGRADE_CONFLICT",
                    "Code version changed after artifact validation"
            );
        }
        return new ConfirmedEvidence(
                version.getId(),
                version.getArtifactSha256(),
                version.getSizeBytes(),
                version.getApprovalStatus()
        );
    }

    private LockedVersion lockVersion(UpgradeSnapshot snapshot) {
        CodeAsset asset = assetRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.assetId())
                .orElseThrow(CodeAssetAccessException::new);
        CodeVersion version = versionRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.versionId())
                .orElseThrow(CodeAssetAccessException::new);
        requireIdentity(asset, version);
        return new LockedVersion(asset, version);
    }

    private PathKind classifyPath(UpgradeSnapshot snapshot) {
        String storagePath = snapshot.storagePath();
        if (isCanonicalPath(storagePath, snapshot)) {
            if (!validSha(snapshot.artifactSha256())
                    || snapshot.sizeBytes() == null
                    || snapshot.sizeBytes() < 0) {
                throw validation(
                        "CANONICAL_ARTIFACT_EVIDENCE_INVALID",
                        "Canonical code artifact evidence is invalid"
                );
            }
            return PathKind.CANONICAL;
        }
        if (isLegacyPath(storagePath, snapshot)) {
            return PathKind.LEGACY;
        }
        throw validation(
                "LEGACY_STORAGE_REFERENCE_INVALID",
                "Legacy code artifact storage reference is invalid"
        );
    }

    private StoredCodeArtifact readAndVerify(String expectedObjectName) {
        StoredCodeArtifact stored = storageService.read(expectedObjectName);
        byte[] bytes = stored.bytes();
        String actualSha256 = filePolicy.sha256(bytes);
        if (!Objects.equals(expectedObjectName, stored.objectName())
                || bytes.length == 0
                || stored.sizeBytes() != bytes.length
                || !validSha(stored.artifactSha256())
                || !Objects.equals(actualSha256, stored.artifactSha256())) {
            throw validation(
                    "ARTIFACT_STORAGE_EVIDENCE_INVALID",
                    "Code artifact storage evidence is invalid"
            );
        }
        return stored;
    }

    private static void requireCanonicalEvidence(
            UpgradeSnapshot snapshot,
            StoredCodeArtifact stored
    ) {
        if (!Objects.equals(snapshot.storagePath(), stored.objectName())
                || !Objects.equals(snapshot.artifactSha256(), stored.artifactSha256())
                || !Objects.equals(snapshot.sizeBytes(), stored.sizeBytes())) {
            throw validation(
                    "CANONICAL_ARTIFACT_EVIDENCE_MISMATCH",
                    "Canonical code artifact evidence does not match"
            );
        }
    }

    private static void requireExactCopy(
            StoredCodeArtifact source,
            StoredCodeArtifact copied,
            String destination
    ) {
        if (!Objects.equals(destination, copied.objectName())
                || !Arrays.equals(source.bytes(), copied.bytes())
                || !Objects.equals(source.artifactSha256(), copied.artifactSha256())
                || source.sizeBytes() != copied.sizeBytes()) {
            throw validation(
                    "ARTIFACT_COPY_MISMATCH",
                    "Copied code artifact evidence does not match"
            );
        }
    }

    private static boolean sameSnapshot(
            CodeAsset asset,
            CodeVersion version,
            UpgradeSnapshot snapshot
    ) {
        return Objects.equals(asset.getId(), snapshot.assetId())
                && Objects.equals(asset.getOwnerUserId(), snapshot.ownerUserId())
                && Objects.equals(asset.getRowVersion(), snapshot.assetRowVersion())
                && Objects.equals(version.getId(), snapshot.versionId())
                && Objects.equals(version.getAssetId(), snapshot.assetId())
                && Objects.equals(version.getOwnerUserId(), snapshot.ownerUserId())
                && Objects.equals(version.getVersion(), snapshot.versionLabel())
                && Objects.equals(version.getFileName(), snapshot.fileName())
                && Objects.equals(version.getStoragePath(), snapshot.storagePath())
                && Objects.equals(version.getSizeBytes(), snapshot.sizeBytes())
                && Objects.equals(version.getStatus(), snapshot.status())
                && Objects.equals(version.getApprovalStatus(), snapshot.approvalStatus())
                && Objects.equals(version.getArtifactSha256(), snapshot.artifactSha256())
                && Objects.equals(version.getValidationStatus(), snapshot.validationStatus())
                && Objects.equals(version.getValidationPolicyVersion(),
                        snapshot.validationPolicyVersion())
                && Objects.equals(version.getPurpose(), snapshot.purpose())
                && Objects.equals(version.getRuntime(), snapshot.runtime())
                && Objects.equals(version.getEntryScript(), snapshot.entryScript())
                && Objects.equals(version.getTrainingType(), snapshot.trainingType())
                && Objects.equals(version.getTrainingProfile(), snapshot.trainingProfile())
                && Objects.equals(version.getUpdatedAt(), snapshot.updatedAt())
                && CodeApprovalStatus.PENDING.equals(version.getApprovalStatus());
    }

    private static boolean sameFrozenMetadata(
            CodeAsset asset,
            CodeVersion version,
            UpgradeSnapshot snapshot
    ) {
        return Objects.equals(asset.getId(), snapshot.assetId())
                && Objects.equals(asset.getOwnerUserId(), snapshot.ownerUserId())
                && Objects.equals(asset.getRowVersion(), snapshot.assetRowVersion())
                && Objects.equals(version.getId(), snapshot.versionId())
                && Objects.equals(version.getAssetId(), snapshot.assetId())
                && Objects.equals(version.getOwnerUserId(), snapshot.ownerUserId())
                && Objects.equals(version.getVersion(), snapshot.versionLabel())
                && Objects.equals(version.getFileName(), snapshot.fileName())
                && Objects.equals(version.getStatus(), snapshot.status())
                && Objects.equals(version.getPurpose(), snapshot.purpose())
                && Objects.equals(version.getRuntime(), snapshot.runtime())
                && Objects.equals(version.getEntryScript(), snapshot.entryScript())
                && Objects.equals(version.getTrainingType(), snapshot.trainingType())
                && Objects.equals(version.getTrainingProfile(), snapshot.trainingProfile());
    }

    private static boolean sameStoredEvidence(
            CodeVersion version,
            StoredCodeArtifact stored
    ) {
        return Objects.equals(version.getStoragePath(), stored.objectName())
                && Objects.equals(version.getArtifactSha256(), stored.artifactSha256())
                && Objects.equals(version.getSizeBytes(), stored.sizeBytes());
    }

    private static void requireIdentity(CodeAsset asset, CodeVersion version) {
        if (asset.getOwnerUserId() == null
                || !Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    private static boolean isLegacyPath(String path, UpgradeSnapshot snapshot) {
        String prefix = assetPrefix(snapshot);
        if (!validObjectText(path) || !path.startsWith(prefix)) {
            return false;
        }
        String expected = prefix
                + sanitizeLegacySegment(snapshot.versionLabel())
                + "/" + sanitizeLegacySegment(snapshot.fileName());
        if (!Objects.equals(path, expected)) {
            return false;
        }
        String[] components = path.substring(prefix.length()).split("/", -1);
        return components.length == 2
                && safeComponent(components[0])
                && safeZipLeaf(components[1]);
    }

    private static String sanitizeLegacySegment(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "unnamed";
        }
        return normalized
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isCanonicalPath(String path, UpgradeSnapshot snapshot) {
        String prefix = assetPrefix(snapshot)
                + "versions/" + snapshot.versionId() + "/";
        if (!validObjectText(path) || !path.startsWith(prefix)) {
            return false;
        }
        return safeZipLeaf(path.substring(prefix.length()));
    }

    private static String canonicalObjectName(UpgradeSnapshot snapshot) {
        return assetPrefix(snapshot)
                + "versions/" + snapshot.versionId()
                + "/" + compactUuid() + ".zip";
    }

    private static String assetPrefix(UpgradeSnapshot snapshot) {
        return "users/" + snapshot.ownerUserId()
                + "/codes/" + snapshot.assetId() + "/";
    }

    private static boolean safeZipLeaf(String component) {
        return safeComponent(component)
                && component.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean safeComponent(String component) {
        return component != null
                && !component.isBlank()
                && !".".equals(component)
                && !"..".equals(component)
                && component.indexOf('/') < 0
                && component.indexOf('\\') < 0
                && component.indexOf('?') < 0
                && component.indexOf('#') < 0
                && component.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validObjectText(String path) {
        return path != null
                && !path.isBlank()
                && path.indexOf('\\') < 0
                && path.indexOf('?') < 0
                && path.indexOf('#') < 0
                && path.chars().noneMatch(Character::isISOControl);
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
            log.error("Code artifact upgrade cleanup enqueue failed: errorType={}",
                    enqueueFailure.getClass().getSimpleName());
            try {
                storageService.delete(objectName);
            } catch (RuntimeException deleteFailure) {
                log.error("Code artifact upgrade fallback cleanup failed: errorType={}",
                        deleteFailure.getClass().getSimpleName());
            }
        }
    }

    private static boolean validSha(String sha256) {
        return sha256 != null && SHA256.matcher(sha256).matches();
    }

    private static String safeReason(String reasonCode) {
        return reasonCode != null && reasonCode.matches("[A-Z0-9_]+")
                ? reasonCode
                : "VALIDATION_FAILED";
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }

    private enum PathKind {
        LEGACY,
        CANONICAL
    }

    private record UpgradeSnapshot(
            String versionId,
            String assetId,
            Integer ownerUserId,
            Long assetRowVersion,
            String versionLabel,
            String fileName,
            String storagePath,
            Long sizeBytes,
            String status,
            String approvalStatus,
            String artifactSha256,
            String validationStatus,
            String validationPolicyVersion,
            String purpose,
            String runtime,
            String entryScript,
            String trainingType,
            String trainingProfile,
            Instant updatedAt
    ) {
    }

    private record LockedVersion(CodeAsset asset, CodeVersion version) {
    }

    private record ConfirmedEvidence(
            String versionId,
            String artifactSha256,
            Long sizeBytes,
            String approvalStatus
    ) {
    }
}
