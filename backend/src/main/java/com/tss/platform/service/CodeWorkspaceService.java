package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CodeWorkspaceService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeWorkspaceRepository workspaceRepository;
    private final CodeAssetAuditService auditService;
    private final AuthContext authContext;

    public CodeWorkspaceService(
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeWorkspaceRepository workspaceRepository,
            CodeAssetAuditService auditService,
            AuthContext authContext
    ) {
        this.assetRepository = assetRepository;
        this.versionRepository = versionRepository;
        this.workspaceRepository = workspaceRepository;
        this.auditService = auditService;
        this.authContext = authContext;
    }

    @Transactional
    public CodeWorkspace open(String assetId, String baseVersionId) {
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        authorizeAsset(asset);

        String normalizedBaseId = normalizeOptionalId(baseVersionId);
        if (normalizedBaseId != null) {
            validateBase(asset, normalizedBaseId);
        }

        CodeWorkspace existing = workspaceRepository.findOpenByAssetIdForUpdate(asset.getId())
                .orElse(null);
        if (existing != null) {
            if (!asset.getOwnerUserId().equals(existing.getOwnerUserId())) {
                throw new CodeAssetAccessException();
            }
            if (Objects.equals(existing.getBaseVersionId(), normalizedBaseId)) {
                return existing;
            }
            throw conflict(
                    "WORKSPACE_BASE_CONFLICT",
                    "An open workspace already uses a different base version"
            );
        }

        Instant now = Instant.now();
        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId("code-workspace-" + UUID.randomUUID().toString().replace("-", ""));
        workspace.setAssetId(asset.getId());
        workspace.setBaseVersionId(normalizedBaseId);
        workspace.setStatus(CodeWorkspace.STATUS_OPEN);
        workspace.setRevision(0L);
        workspace.setOwnerUserId(asset.getOwnerUserId());
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspace.setDeleted(false);
        workspaceRepository.save(workspace);
        auditService.workspaceOpened(asset.getId(), workspace.getId(), 0L);
        return workspace;
    }

    @Transactional
    public CodeWorkspace abandon(String workspaceId, long expectedRevision) {
        String assetId = workspaceRepository.findAssetIdByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        authorizeAsset(asset);

        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!asset.getId().equals(workspace.getAssetId())
                || !asset.getOwnerUserId().equals(workspace.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        authorizeAsset(asset);
        requireOpen(workspace);
        requireRevision(workspace, expectedRevision);

        Instant now = Instant.now();
        workspace.setStatus(CodeWorkspace.STATUS_ABANDONED);
        workspace.setRevision(workspace.getRevision() + 1);
        workspace.setUpdatedAt(now);
        workspace.setClosedAt(now);
        workspaceRepository.save(workspace);
        auditService.workspaceAbandoned(
                asset.getId(),
                workspace.getId(),
                workspace.getRevision()
        );
        return workspace;
    }

    private void validateBase(CodeAsset asset, String baseVersionId) {
        CodeVersion version = versionRepository
                .findByIdAndAssetIdAndDeletedFalse(baseVersionId, asset.getId())
                .orElseThrow(CodeAssetAccessException::new);
        if (!asset.getOwnerUserId().equals(version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        if (version.getStoragePath() == null
                || version.getStoragePath().isBlank()
                || !canAccessObject(version.getStoragePath(), asset.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        if (!"READY".equals(version.getStatus())) {
            throw validation("BASE_VERSION_NOT_READY", "Base code version is not ready");
        }
        if (!"PASSED".equals(version.getValidationStatus())) {
            throw validation(
                    "BASE_VERSION_NOT_VALIDATED",
                    "Base code version has not passed validation"
            );
        }
        if (version.getArtifactSha256() == null
                || !SHA256.matcher(version.getArtifactSha256()).matches()) {
            throw validation(
                    "BASE_VERSION_EVIDENCE_MISSING",
                    "Base code version has no artifact evidence"
            );
        }
    }

    private boolean canAccessObject(String objectName, Integer ownerUserId) {
        try {
            return authContext.canAccessObjectName(objectName, ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void authorizeAsset(CodeAsset asset) {
        if (asset.getOwnerUserId() == null) {
            throw new CodeAssetAccessException();
        }
        boolean allowed;
        try {
            allowed = authContext.canAccessOwner(asset.getOwnerUserId());
        } catch (RuntimeException exception) {
            allowed = false;
        }
        if (!allowed) {
            throw new CodeAssetAccessException();
        }
    }

    private static void requireOpen(CodeWorkspace workspace) {
        if (!CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus())) {
            throw conflict("WORKSPACE_READ_ONLY", "Code workspace is read-only");
        }
    }

    private static void requireRevision(CodeWorkspace workspace, long expectedRevision) {
        if (workspace.getRevision() == null
                || workspace.getRevision() != expectedRevision) {
            throw conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    "Code workspace revision is stale"
            );
        }
    }

    private static String normalizeOptionalId(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }
}
