package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CodeWorkspacePublishService {

    private static final Logger log = LoggerFactory.getLogger(CodeWorkspacePublishService.class);
    private static final Pattern VERSION_LABEL = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CodeWorkspaceRepository workspaceRepository;
    private final CodeWorkspaceFileDeltaRepository deltaRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeValidationRunRepository validationRunRepository;
    private final CodeArtifactAssembler assembler;
    private final CodeArtifactStorageService storageService;
    private final MinioDeleteTaskService deleteTaskService;
    private final CodeAssetAuditService auditService;
    private final CodeRiskAssessmentService riskAssessmentService;
    private final AuthContext authContext;
    private final TransactionTemplate transactionTemplate;

    public CodeWorkspacePublishService(
            CodeWorkspaceRepository workspaceRepository,
            CodeWorkspaceFileDeltaRepository deltaRepository,
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeValidationRunRepository validationRunRepository,
            CodeArtifactAssembler assembler,
            CodeArtifactStorageService storageService,
            MinioDeleteTaskService deleteTaskService,
            CodeAssetAuditService auditService,
            CodeRiskAssessmentService riskAssessmentService,
            AuthContext authContext,
            PlatformTransactionManager transactionManager
    ) {
        this.workspaceRepository = workspaceRepository;
        this.deltaRepository = deltaRepository;
        this.assetRepository = assetRepository;
        this.versionRepository = versionRepository;
        this.validationRunRepository = validationRunRepository;
        this.assembler = assembler;
        this.storageService = storageService;
        this.deleteTaskService = deleteTaskService;
        this.auditService = auditService;
        this.riskAssessmentService = riskAssessmentService;
        this.authContext = authContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public CodeVersion publish(String workspaceId, long expectedRevision, String versionLabel) {
        String label = normalizeLabel(versionLabel);
        PublishSnapshot snapshot = transactionTemplate.execute(status ->
                snapshot(workspaceId, expectedRevision, label)
        );
        if (snapshot == null) {
            throw new CodeWorkspacePublishException();
        }

        MaterializedCodeArtifact materialized = assembler.materialize(
                snapshot.asset(), snapshot.baseVersion(), snapshot.deltas()
        );
        if (!materialized.validation().passed()) {
            throw new CodeValidationException(
                    materialized.validation().reasonCode(),
                    materialized.validation().safeMessage()
            );
        }

        String versionId = "code-version-" + compactUuid();
        String objectName = "users/" + snapshot.ownerUserId()
                + "/codes/" + snapshot.assetId()
                + "/versions/" + versionId
                + "/" + compactUuid() + ".zip";
        boolean uploadAttempted = false;
        try {
            uploadAttempted = true;
            byte[] expectedBytes = materialized.bytes();
            storageService.upload(objectName, expectedBytes);
            StoredCodeArtifact stored = storageService.read(objectName);
            if (!Arrays.equals(expectedBytes, stored.bytes())) {
                throw new CodeValidationException(
                        "STORED_ARTIFACT_CHANGED",
                        "Stored code artifact changed after upload"
                );
            }
            CodeValidationResult storedValidation = assembler.validateOrThrow(
                    snapshot.asset(), stored.bytes()
            );
            if (!storedValidation.passed()
                    || !stored.artifactSha256().equals(storedValidation.artifactSha256())) {
                throw new CodeValidationException(
                        "STORED_ARTIFACT_INVALID",
                        "Stored code artifact validation failed"
                );
            }

            CodeVersion published = transactionTemplate.execute(status -> finalizePublish(
                    snapshot,
                    label,
                    versionId,
                    objectName,
                    stored,
                    storedValidation
            ));
            if (published == null) {
                throw new CodeWorkspacePublishException();
            }
            return published;
        } catch (RuntimeException exception) {
            if (uploadAttempted) {
                compensate(objectName, versionId, snapshot.ownerUserId());
            }
            if (exception instanceof CodeAssetAccessException
                    || exception instanceof CodeWorkspaceConflictException
                    || exception instanceof CodeValidationException
                    || exception instanceof CodeArtifactStorageException
                    || exception instanceof CodeWorkspacePublishException) {
                throw exception;
            }
            throw new CodeWorkspacePublishException();
        }
    }

    private PublishSnapshot snapshot(
            String workspaceId,
            long expectedRevision,
            String label
    ) {
        String assetId = workspaceRepository.findAssetIdByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        authorizeOwner(asset.getOwnerUserId());
        authContext.rejectDemoWrite(asset.getIsDemo());
        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        requireWorkspaceIdentity(asset, workspace);
        requireOpenAndRevision(workspace, expectedRevision);
        if (versionRepository.existsByAssetIdAndVersion(asset.getId(), label)) {
            throw conflict("VERSION_LABEL_CONFLICT", "Code version label already exists");
        }
        CodeVersion base = resolveBase(asset, workspace.getBaseVersionId());
        return new PublishSnapshot(
                CodeValidationService.copyAsset(asset),
                base == null ? null : CodeValidationService.copyVersion(base),
                copyDeltas(deltaRepository.findByWorkspaceIdOrderByPathAsc(workspaceId)),
                asset.getId(),
                asset.getOwnerUserId(),
                workspace.getId(),
                workspace.getBaseVersionId(),
                workspace.getRevision(),
                asset.getRowVersion()
        );
    }

    private CodeVersion finalizePublish(
            PublishSnapshot snapshot,
            String label,
            String versionId,
            String objectName,
            StoredCodeArtifact stored,
            CodeValidationResult validation
    ) {
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(snapshot.assetId())
                .orElseThrow(CodeAssetAccessException::new);
        authorizeOwner(asset.getOwnerUserId());
        authContext.rejectDemoWrite(asset.getIsDemo());
        CodeWorkspace workspace = workspaceRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.workspaceId())
                .orElseThrow(CodeAssetAccessException::new);
        requireWorkspaceIdentity(asset, workspace);
        requireOpenAndRevision(workspace, snapshot.workspaceRevision());
        if (!Objects.equals(workspace.getBaseVersionId(), snapshot.baseVersionId())
                || !sameAssetSnapshot(asset, snapshot)) {
            throw conflict("WORKSPACE_PUBLISH_CONFLICT", "Code workspace changed during publish");
        }
        requireUnchangedBaseVersion(snapshot);
        if (versionRepository.existsByAssetIdAndVersion(asset.getId(), label)) {
            throw conflict("VERSION_LABEL_CONFLICT", "Code version label already exists");
        }

        Instant now = Instant.now();
        CodeVersion version = new CodeVersion();
        version.setId(versionId);
        version.setAssetId(asset.getId());
        version.setVersion(label);
        version.setFileName(label + ".zip");
        version.setStoragePath(objectName);
        version.setSizeBytes(stored.sizeBytes());
        version.setPurpose(snapshot.asset().getPurpose());
        version.setRuntime(snapshot.asset().getRuntime());
        version.setEntryScript(assembler.effectiveEntryScript(snapshot.asset()));
        version.setTrainingType(snapshot.asset().getTrainingType());
        version.setTrainingProfile(snapshot.asset().getTrainingProfile());
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
        run.setRequestedByUserId(authContext.currentUserId());
        run.setCreatedAt(now);
        run.setCompletedAt(now);
        validationRunRepository.saveAndFlush(run);

        riskAssessmentService.enqueue(
                version.getId(), run.getId(), asset.getOwnerUserId()
        );

        workspace.setStatus(CodeWorkspace.STATUS_PUBLISHED);
        workspace.setClosedVersionId(versionId);
        workspace.setRevision(workspace.getRevision() + 1);
        workspace.setUpdatedAt(now);
        workspace.setClosedAt(now);
        workspaceRepository.saveAndFlush(workspace);
        auditService.published(
                asset.getId(),
                versionId,
                workspace.getId(),
                workspace.getRevision(),
                validation.fileCount(),
                stored.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION
        );
        return version;
    }

    private CodeVersion resolveBase(CodeAsset asset, String baseVersionId) {
        if (baseVersionId == null) {
            return null;
        }
        CodeVersion base = versionRepository
                .findByIdAndAssetIdAndDeletedFalse(baseVersionId, asset.getId())
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(asset.getOwnerUserId(), base.getOwnerUserId())
                || !"READY".equals(base.getStatus())
                || !"PASSED".equals(base.getValidationStatus())
                || base.getArtifactSha256() == null
                || !SHA256.matcher(base.getArtifactSha256()).matches()
                || !canAccessObject(base.getStoragePath(), asset.getOwnerUserId())) {
            throw new CodeValidationException(
                    "BASE_VERSION_INVALID",
                    "Base code version is invalid"
            );
        }
        return base;
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
            log.error("Code artifact cleanup enqueue failed: errorType={}",
                    enqueueFailure.getClass().getSimpleName());
            try {
                storageService.delete(objectName);
            } catch (RuntimeException deleteFailure) {
                log.error("Code artifact fallback cleanup failed: errorType={}",
                        deleteFailure.getClass().getSimpleName());
            }
        }
    }

    private static String normalizeLabel(String versionLabel) {
        if (versionLabel == null) {
            throw new CodeValidationException(
                    "VERSION_LABEL_INVALID",
                    "Code version label is invalid"
            );
        }
        String label = versionLabel.trim();
        if (label.isEmpty() || label.length() > 64 || !VERSION_LABEL.matcher(label).matches()) {
            throw new CodeValidationException(
                    "VERSION_LABEL_INVALID",
                    "Code version label is invalid"
            );
        }
        return label;
    }

    private void requireWorkspaceIdentity(CodeAsset asset, CodeWorkspace workspace) {
        if (!Objects.equals(asset.getId(), workspace.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), workspace.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    private static void requireOpenAndRevision(CodeWorkspace workspace, long revision) {
        if (!CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus())) {
            throw conflict("WORKSPACE_READ_ONLY", "Code workspace is read-only");
        }
        if (!Objects.equals(workspace.getRevision(), revision)) {
            throw conflict("WORKSPACE_REVISION_CONFLICT", "Code workspace revision is stale");
        }
    }

    private boolean sameAssetSnapshot(CodeAsset asset, PublishSnapshot snapshot) {
        CodeAsset before = snapshot.asset();
        return Objects.equals(asset.getRowVersion(), snapshot.assetRowVersion())
                && Objects.equals(asset.getOwnerUserId(), before.getOwnerUserId())
                && Objects.equals(asset.getName(), before.getName())
                && Objects.equals(asset.getPurpose(), before.getPurpose())
                && Objects.equals(asset.getRuntime(), before.getRuntime())
                && Objects.equals(asset.getEntryScript(), before.getEntryScript())
                && Objects.equals(asset.getTrainingType(), before.getTrainingType())
                && Objects.equals(asset.getTrainingProfile(), before.getTrainingProfile());
    }

    private void requireUnchangedBaseVersion(PublishSnapshot snapshot) {
        if (snapshot.baseVersionId() == null) {
            return;
        }
        CodeVersion before = snapshot.baseVersion();
        CodeVersion current = versionRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.baseVersionId())
                .orElseThrow(() -> conflict(
                        "BASE_VERSION_CHANGED", "Base code version changed during publish"
                ));
        if (before == null
                || !Objects.equals(current.getAssetId(), snapshot.assetId())
                || !Objects.equals(current.getAssetId(), before.getAssetId())
                || !Objects.equals(current.getOwnerUserId(), before.getOwnerUserId())
                || !Objects.equals(current.getStatus(), before.getStatus())
                || !Objects.equals(current.getValidationStatus(), before.getValidationStatus())
                || !Objects.equals(current.getValidationPolicyVersion(),
                        before.getValidationPolicyVersion())
                || !Objects.equals(current.getArtifactSha256(), before.getArtifactSha256())
                || !Objects.equals(current.getStoragePath(), before.getStoragePath())
                || !"READY".equals(current.getStatus())
                || !"PASSED".equals(current.getValidationStatus())
                || !canAccessObject(current.getStoragePath(), snapshot.ownerUserId())) {
            throw conflict(
                    "BASE_VERSION_CHANGED", "Base code version changed during publish"
            );
        }
    }

    private void authorizeOwner(Integer ownerUserId) {
        if (ownerUserId == null || !canAccessOwner(ownerUserId)) {
            throw new CodeAssetAccessException();
        }
    }

    private boolean canAccessOwner(Integer ownerUserId) {
        try {
            return authContext.canAccessOwner(ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean canAccessObject(String objectName, Integer ownerUserId) {
        if (objectName == null || objectName.isBlank()) {
            return false;
        }
        try {
            return authContext.canAccessObjectName(objectName, ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<CodeWorkspaceFileDelta> copyDeltas(List<CodeWorkspaceFileDelta> source) {
        return source.stream().map(delta -> {
            CodeWorkspaceFileDelta copy = new CodeWorkspaceFileDelta();
            copy.setId(delta.getId());
            copy.setWorkspaceId(delta.getWorkspaceId());
            copy.setPath(delta.getPath());
            copy.setOperation(delta.getOperation());
            copy.setContentBytes(delta.getContentBytes());
            copy.setContentHash(delta.getContentHash());
            copy.setSizeBytes(delta.getSizeBytes());
            copy.setCreatedAt(delta.getCreatedAt());
            copy.setUpdatedAt(delta.getUpdatedAt());
            return copy;
        }).toList();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }

    private record PublishSnapshot(
            CodeAsset asset,
            CodeVersion baseVersion,
            List<CodeWorkspaceFileDelta> deltas,
            String assetId,
            Integer ownerUserId,
            String workspaceId,
            String baseVersionId,
            long workspaceRevision,
            Long assetRowVersion
    ) {
    }
}
