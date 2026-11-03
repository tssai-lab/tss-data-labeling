package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CodeValidationService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CodeWorkspaceRepository workspaceRepository;
    private final CodeWorkspaceFileDeltaRepository deltaRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeValidationRunRepository validationRunRepository;
    private final CodeArtifactStorageService storageService;
    private final CodeArtifactAssembler assembler;
    private final CodeAssetAuditService auditService;
    private final CodeRiskAssessmentService riskAssessmentService;
    private final AuthContext authContext;
    private final TransactionTemplate transactionTemplate;

    public CodeValidationService(
            CodeWorkspaceRepository workspaceRepository,
            CodeWorkspaceFileDeltaRepository deltaRepository,
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeValidationRunRepository validationRunRepository,
            CodeArtifactStorageService storageService,
            CodeArtifactAssembler assembler,
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
        this.storageService = storageService;
        this.assembler = assembler;
        this.auditService = auditService;
        this.riskAssessmentService = riskAssessmentService;
        this.authContext = authContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public CodeValidationResult validateWorkspace(String workspaceId, long expectedRevision) {
        WorkspaceSnapshot snapshot = transactionTemplate.execute(status ->
                snapshotWorkspace(workspaceId, expectedRevision)
        );
        if (snapshot == null) {
            throw new IllegalStateException("Code workspace validation transaction failed");
        }
        CodeValidationResult observed;
        try {
            observed = assembler.materialize(
                    snapshot.asset(), snapshot.baseVersion(), snapshot.deltas()
            ).validation();
        } catch (CodeValidationException exception) {
            observed = new CodeValidationResult(
                    CodeArtifactAssembler.POLICY_VERSION,
                    "0".repeat(64),
                    "FAILED",
                    exception.getReasonCode(),
                    "Code artifact validation failed",
                    0
            );
        } catch (CodeArtifactStorageException exception) {
            observed = storageFailure("0".repeat(64));
        }
        CodeValidationResult finalObserved = observed;
        Boolean audited = transactionTemplate.execute(status -> {
            auditWorkspaceValidation(snapshot, finalObserved);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(audited)) {
            throw new IllegalStateException("Code workspace validation audit transaction failed");
        }
        return observed;
    }

    public CodeValidationResult validateVersion(String versionId) {
        VersionSnapshot snapshot = transactionTemplate.execute(status ->
                versionSnapshot(versionId)
        );
        if (snapshot == null) {
            throw new IllegalStateException("Code version validation transaction failed");
        }
        VersionValidationObservation observation;
        try {
            StoredCodeArtifact stored = storageService.read(snapshot.storagePath());
            CodeValidationResult observed = assembler.validate(snapshot.asset(), stored.bytes());
            if (!Objects.equals(stored.objectName(), snapshot.storagePath())) {
                observed = failed(
                        stored.artifactSha256(),
                        "STORAGE_REFERENCE_INVALID",
                        "Code artifact storage reference is invalid",
                        observed.fileCount()
                );
            } else if (!stored.artifactSha256().equals(snapshot.expectedSha256())) {
                observed = failed(
                        stored.artifactSha256(),
                        "ARTIFACT_SHA256_MISMATCH",
                        "Code artifact hash does not match",
                        observed.fileCount()
                );
            } else if (snapshot.expectedSizeBytes() == null
                    || snapshot.expectedSizeBytes() < 0) {
                observed = failed(
                        stored.artifactSha256(),
                        "VERSION_EVIDENCE_MISSING",
                        "Code version artifact evidence is missing",
                        observed.fileCount()
                );
            } else if (stored.sizeBytes() != snapshot.expectedSizeBytes()) {
                observed = failed(
                        stored.artifactSha256(),
                        "ARTIFACT_SIZE_MISMATCH",
                        "Code artifact size does not match",
                        observed.fileCount()
                );
            }
            observation = new VersionValidationObservation(
                    observed,
                    stored.objectName(),
                    stored.artifactSha256(),
                    stored.sizeBytes()
            );
        } catch (CodeArtifactStorageException exception) {
            observation = new VersionValidationObservation(
                    storageFailure(snapshot.expectedSha256()),
                    null,
                    null,
                    null
            );
        }

        VersionValidationObservation finalObservation = observation;
        CodeValidationResult saved = transactionTemplate.execute(status ->
                persistVersionValidation(snapshot, finalObservation)
        );
        if (saved == null) {
            throw new IllegalStateException("Code version validation transaction failed");
        }
        return saved;
    }

    private WorkspaceSnapshot snapshotWorkspace(String workspaceId, long expectedRevision) {
        String assetId = workspaceRepository.findAssetIdByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        authorizeOwner(asset.getOwnerUserId());
        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        requireWorkspaceIdentity(asset, workspace);
        if (!CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus())) {
            throw conflict("WORKSPACE_READ_ONLY", "Code workspace is read-only");
        }
        if (!Objects.equals(workspace.getRevision(), expectedRevision)) {
            throw conflict("WORKSPACE_REVISION_CONFLICT", "Code workspace revision is stale");
        }
        CodeVersion base = resolveBase(asset, workspace.getBaseVersionId());
        return new WorkspaceSnapshot(
                copyAsset(asset),
                base == null ? null : copyVersion(base),
                copyDeltas(deltaRepository.findByWorkspaceIdOrderByPathAsc(workspaceId)),
                asset.getId(),
                workspace.getId(),
                workspace.getBaseVersionId(),
                workspace.getRevision(),
                asset.getRowVersion()
        );
    }

    private void auditWorkspaceValidation(
            WorkspaceSnapshot snapshot,
            CodeValidationResult observed
    ) {
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(snapshot.assetId())
                .orElseThrow(CodeAssetAccessException::new);
        authorizeOwner(asset.getOwnerUserId());
        CodeWorkspace workspace = workspaceRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.workspaceId())
                .orElseThrow(CodeAssetAccessException::new);
        requireWorkspaceIdentity(asset, workspace);
        if (!CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus())) {
            throw conflict("WORKSPACE_READ_ONLY", "Code workspace is read-only");
        }
        if (!Objects.equals(workspace.getRevision(), snapshot.workspaceRevision())) {
            throw conflict("WORKSPACE_REVISION_CONFLICT", "Code workspace revision is stale");
        }
        if (!Objects.equals(workspace.getBaseVersionId(), snapshot.baseVersionId())
                || !sameAssetSnapshot(asset, snapshot)) {
            throw conflict("WORKSPACE_VALIDATION_CONFLICT",
                    "Code workspace changed during validation");
        }
        requireUnchangedBaseVersion(snapshot);
        auditService.workspaceValidated(
                asset.getId(),
                workspace.getId(),
                workspace.getRevision(),
                observed.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION,
                observed.passed() ? "VALIDATION_PASSED" : observed.reasonCode(),
                observed.fileCount()
        );
    }

    private VersionSnapshot versionSnapshot(String versionId) {
        CodeVersion version = versionRepository.findByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        if (!asset.getId().equals(version.getAssetId())
                || asset.getOwnerUserId() == null
                || !asset.getOwnerUserId().equals(version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        authorizeOwner(asset.getOwnerUserId());
        String expectedSha = version.getArtifactSha256();
        if (expectedSha == null || !SHA256.matcher(expectedSha).matches()) {
            throw validation("VERSION_EVIDENCE_MISSING", "Code version artifact evidence is missing");
        }
        if (!canAccessObject(version.getStoragePath(), asset.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        return new VersionSnapshot(
                validationMetadata(version),
                version.getId(),
                version.getAssetId(),
                version.getOwnerUserId(),
                version.getStoragePath(),
                expectedSha,
                version.getSizeBytes()
        );
    }

    private CodeValidationResult persistVersionValidation(
            VersionSnapshot snapshot,
            VersionValidationObservation observation
    ) {
        CodeValidationResult observed = observation.result();
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(snapshot.assetId())
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(asset.getOwnerUserId(), snapshot.ownerUserId())) {
            throw new CodeAssetAccessException();
        }
        authorizeOwner(asset.getOwnerUserId());
        CodeVersion locked = versionRepository.findByIdAndDeletedFalseForUpdate(snapshot.versionId())
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(locked.getAssetId(), snapshot.assetId())
                || !Objects.equals(locked.getOwnerUserId(), snapshot.ownerUserId())
                || !Objects.equals(locked.getStoragePath(), snapshot.storagePath())
                || !Objects.equals(locked.getArtifactSha256(), snapshot.expectedSha256())
                || !Objects.equals(locked.getSizeBytes(), snapshot.expectedSizeBytes())) {
            throw conflict("VERSION_CHANGED", "Code version changed during validation");
        }

        if (canReuseValidationEvidence(locked, observation)) {
            CodeValidationRun latest = validationRunRepository
                    .findTopByVersionIdOrderByCreatedAtDescIdDesc(locked.getId())
                    .orElse(null);
            if (isCurrentPassingEvidence(locked, latest)) {
                return observed.asReused();
            }
        }

        Instant now = Instant.now();
        CodeValidationRun run = new CodeValidationRun();
        run.setId("code-validation-" + compactUuid());
        run.setVersionId(locked.getId());
        run.setArtifactSha256(observed.artifactSha256());
        run.setPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        run.setStatus(observed.status());
        run.setFailureCode(observed.reasonCode());
        run.setFailureMessage(observed.passed() ? null : observed.safeMessage());
        run.setRequestedByUserId(authContext.currentUserId());
        run.setCreatedAt(now);
        run.setCompletedAt(now);
        validationRunRepository.saveAndFlush(run);

        locked.setValidationStatus(observed.status());
        locked.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        if ("APPROVED".equals(locked.getApprovalStatus())) {
            // Only genuinely new evidence invalidates the old approval binding.
            // Equivalent repeated validation returned above without any mutation.
            locked.setApprovalStatus("PENDING");
        }
        locked.setUpdatedAt(now);
        versionRepository.saveAndFlush(locked);
        if (observed.passed()) {
            riskAssessmentService.enqueue(
                    locked.getId(), run.getId(), authContext.currentUserId()
            );
        }
        auditService.validated(
                asset.getId(),
                locked.getId(),
                observed.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION,
                observed.passed() ? "VALIDATION_PASSED" : observed.reasonCode(),
                observed.fileCount()
        );
        return observed;
    }

    private boolean canReuseValidationEvidence(
            CodeVersion version,
            VersionValidationObservation observation
    ) {
        return observation.result().passed()
                && Objects.equals(observation.objectName(), version.getStoragePath())
                && Objects.equals(observation.artifactSha256(), version.getArtifactSha256())
                && version.getSizeBytes() != null
                && version.getSizeBytes() >= 0
                && Objects.equals(observation.sizeBytes(), version.getSizeBytes())
                && "PASSED".equals(version.getValidationStatus())
                && CodeArtifactAssembler.POLICY_VERSION.equals(
                        version.getValidationPolicyVersion()
                );
    }

    private boolean isCurrentPassingEvidence(
            CodeVersion version,
            CodeValidationRun run
    ) {
        return run != null
                && "PASSED".equals(run.getStatus())
                && Objects.equals(run.getArtifactSha256(), version.getArtifactSha256())
                && CodeArtifactAssembler.POLICY_VERSION.equals(run.getPolicyVersion());
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
            throw validation("BASE_VERSION_INVALID", "Base code version is invalid");
        }
        return base;
    }

    private boolean sameAssetSnapshot(CodeAsset current, WorkspaceSnapshot snapshot) {
        CodeAsset before = snapshot.asset();
        return Objects.equals(current.getRowVersion(), snapshot.assetRowVersion())
                && Objects.equals(current.getOwnerUserId(), before.getOwnerUserId())
                && Objects.equals(current.getName(), before.getName())
                && Objects.equals(current.getPurpose(), before.getPurpose())
                && Objects.equals(current.getRuntime(), before.getRuntime())
                && Objects.equals(current.getEntryScript(), before.getEntryScript())
                && Objects.equals(current.getTrainingType(), before.getTrainingType())
                && Objects.equals(current.getTrainingProfile(), before.getTrainingProfile());
    }

    private void requireUnchangedBaseVersion(WorkspaceSnapshot snapshot) {
        if (snapshot.baseVersionId() == null) {
            return;
        }
        CodeVersion before = snapshot.baseVersion();
        CodeVersion current = versionRepository
                .findByIdAndDeletedFalseForUpdate(snapshot.baseVersionId())
                .orElseThrow(() -> conflict(
                        "BASE_VERSION_CHANGED", "Base code version changed during validation"
                ));
        if (before == null
                || !Objects.equals(current.getAssetId(), snapshot.assetId())
                || !Objects.equals(current.getOwnerUserId(), before.getOwnerUserId())
                || !Objects.equals(current.getStatus(), before.getStatus())
                || !Objects.equals(current.getValidationStatus(), before.getValidationStatus())
                || !Objects.equals(current.getValidationPolicyVersion(),
                        before.getValidationPolicyVersion())
                || !Objects.equals(current.getArtifactSha256(), before.getArtifactSha256())
                || !Objects.equals(current.getStoragePath(), before.getStoragePath())
                || !"READY".equals(current.getStatus())
                || !"PASSED".equals(current.getValidationStatus())
                || !canAccessObject(current.getStoragePath(), before.getOwnerUserId())) {
            throw conflict(
                    "BASE_VERSION_CHANGED", "Base code version changed during validation"
            );
        }
    }

    private void requireWorkspaceIdentity(CodeAsset asset, CodeWorkspace workspace) {
        if (!Objects.equals(asset.getId(), workspace.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), workspace.getOwnerUserId())) {
            throw new CodeAssetAccessException();
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

    private static CodeValidationResult storageFailure(String expectedSha) {
        return failed(expectedSha, "STORAGE_READ_FAILED", "Code artifact could not be read", 0);
    }

    private static CodeValidationResult failed(
            String sha,
            String reasonCode,
            String message,
            int fileCount
    ) {
        return new CodeValidationResult(
                CodeArtifactAssembler.POLICY_VERSION,
                sha,
                "FAILED",
                reasonCode,
                message,
                Math.max(0, fileCount)
        );
    }

    private static List<CodeWorkspaceFileDelta> copyDeltas(List<CodeWorkspaceFileDelta> source) {
        List<CodeWorkspaceFileDelta> copies = new ArrayList<>();
        for (CodeWorkspaceFileDelta delta : source) {
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
            copies.add(copy);
        }
        return List.copyOf(copies);
    }

    static CodeAsset copyAsset(CodeAsset source) {
        CodeAsset copy = new CodeAsset();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setTrainingProfile(source.getTrainingProfile());
        copy.setPurpose(source.getPurpose());
        copy.setRuntime(source.getRuntime());
        copy.setEntryScript(source.getEntryScript());
        copy.setTrainingType(source.getTrainingType());
        copy.setRemark(source.getRemark());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setRowVersion(source.getRowVersion());
        copy.setDeleted(source.getDeleted());
        return copy;
    }

    static CodeVersion copyVersion(CodeVersion source) {
        CodeVersion copy = new CodeVersion();
        copy.setId(source.getId());
        copy.setAssetId(source.getAssetId());
        copy.setVersion(source.getVersion());
        copy.setStoragePath(source.getStoragePath());
        copy.setArtifactSha256(source.getArtifactSha256());
        copy.setValidationStatus(source.getValidationStatus());
        copy.setValidationPolicyVersion(source.getValidationPolicyVersion());
        copy.setPurpose(source.getPurpose());
        copy.setRuntime(source.getRuntime());
        copy.setEntryScript(source.getEntryScript());
        copy.setTrainingType(source.getTrainingType());
        copy.setTrainingProfile(source.getTrainingProfile());
        copy.setStatus(source.getStatus());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setDeleted(source.getDeleted());
        return copy;
    }

    private static CodeAsset validationMetadata(CodeVersion version) {
        CodeAsset metadata = new CodeAsset();
        metadata.setId(version.getAssetId());
        metadata.setPurpose(version.getPurpose());
        metadata.setRuntime(version.getRuntime());
        metadata.setEntryScript(version.getEntryScript());
        metadata.setTrainingType(version.getTrainingType());
        metadata.setTrainingProfile(version.getTrainingProfile());
        metadata.setOwnerUserId(version.getOwnerUserId());
        metadata.setDeleted(false);
        return metadata;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private record WorkspaceSnapshot(
            CodeAsset asset,
            CodeVersion baseVersion,
            List<CodeWorkspaceFileDelta> deltas,
            String assetId,
            String workspaceId,
            String baseVersionId,
            long workspaceRevision,
            Long assetRowVersion
    ) {
    }

    private record VersionSnapshot(
            CodeAsset asset,
            String versionId,
            String assetId,
            Integer ownerUserId,
            String storagePath,
            String expectedSha256,
            Long expectedSizeBytes
    ) {
    }

    private record VersionValidationObservation(
            CodeValidationResult result,
            String objectName,
            String artifactSha256,
            Long sizeBytes
    ) {
    }
}
