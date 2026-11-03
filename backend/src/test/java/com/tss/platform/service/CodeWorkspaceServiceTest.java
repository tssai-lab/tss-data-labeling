package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeWorkspaceServiceTest {

    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeWorkspaceRepository workspaceRepository = mock(CodeWorkspaceRepository.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final AuthContext authContext = mock(AuthContext.class);

    private CodeWorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new CodeWorkspaceService(
                assetRepository,
                versionRepository,
                workspaceRepository,
                auditService,
                authContext
        );
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(authContext.canAccessObjectName("users/7/codes/asset-1/base.zip", 7)).thenReturn(true);
    }

    @Test
    void opensWorkspaceAfterAssetLockAndRecordsSafeAudit() {
        CodeAsset asset = asset("asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(workspaceRepository.findOpenByAssetIdForUpdate("asset-1"))
                .thenReturn(Optional.empty());
        when(workspaceRepository.save(any(CodeWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CodeWorkspace opened = service.open("asset-1", null);

        assertNotNull(opened.getId());
        assertEquals("asset-1", opened.getAssetId());
        assertEquals(7, opened.getOwnerUserId());
        assertEquals(CodeWorkspace.STATUS_OPEN, opened.getStatus());
        assertEquals(0L, opened.getRevision());
        verify(auditService).workspaceOpened("asset-1", opened.getId(), 0L);

        InOrder order = inOrder(assetRepository, workspaceRepository);
        order.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        order.verify(workspaceRepository).findOpenByAssetIdForUpdate("asset-1");
        order.verify(workspaceRepository).save(opened);
    }

    @Test
    void reopensSameBaseIdempotentlyWithoutSecondAudit() {
        CodeAsset asset = asset("asset-1", 7);
        CodeWorkspace existing = workspace("workspace-1", "asset-1", 7, "version-1");
        CodeVersion base = readyPassedBase("version-1", "asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("version-1", "asset-1"))
                .thenReturn(Optional.of(base));
        when(workspaceRepository.findOpenByAssetIdForUpdate("asset-1"))
                .thenReturn(Optional.of(existing));

        CodeWorkspace reopened = service.open("asset-1", "version-1");

        assertSame(existing, reopened);
        verify(workspaceRepository, never()).save(any());
        verify(auditService, never()).workspaceOpened(any(), any(), any(Long.class));
    }

    @Test
    void rejectsDifferentBaseForExistingOpenWorkspace() {
        CodeAsset asset = asset("asset-1", 7);
        CodeWorkspace existing = workspace("workspace-1", "asset-1", 7, "version-1");
        CodeVersion requestedBase = readyPassedBase("version-2", "asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("version-2", "asset-1"))
                .thenReturn(Optional.of(requestedBase));
        when(workspaceRepository.findOpenByAssetIdForUpdate("asset-1"))
                .thenReturn(Optional.of(existing));

        CodeWorkspaceConflictException error = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.open("asset-1", "version-2")
        );

        assertEquals("WORKSPACE_BASE_CONFLICT", error.getReasonCode());
        verify(workspaceRepository, never()).save(any());
        verify(auditService, never()).workspaceOpened(any(), any(), any(Long.class));
    }

    @Test
    void hidesMissingCrossOwnerNullOwnerAndCrossAssetBase() {
        when(assetRepository.findByIdAndDeletedFalseForUpdate("missing"))
                .thenReturn(Optional.empty());
        assertThrows(CodeAssetAccessException.class, () -> service.open("missing", null));

        CodeAsset crossOwner = asset("asset-cross", 9);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-cross"))
                .thenReturn(Optional.of(crossOwner));
        when(authContext.canAccessOwner(9)).thenReturn(false);
        assertThrows(CodeAssetAccessException.class, () -> service.open("asset-cross", null));

        CodeAsset nullOwner = asset("asset-null", null);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-null"))
                .thenReturn(Optional.of(nullOwner));
        when(authContext.canAccessOwner(null)).thenReturn(true);
        assertThrows(CodeAssetAccessException.class, () -> service.open("asset-null", null));

        CodeAsset owned = asset("asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("other-version", "asset-1"))
                .thenReturn(Optional.empty());
        assertThrows(
                CodeAssetAccessException.class,
                () -> service.open("asset-1", "other-version")
        );
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void authorizationProviderFailuresRemainGenericAndDoNotLeakContext() {
        CodeAsset asset = asset("asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(authContext.canAccessOwner(7))
                .thenThrow(new IllegalStateException("users/7/private-context"));

        CodeAssetAccessException error = assertThrows(
                CodeAssetAccessException.class,
                () -> service.open("asset-1", null)
        );

        org.junit.jupiter.api.Assertions.assertFalse(
                error.getMessage().contains("users/7/private-context")
        );
        assertEquals(null, error.getCause());
        verify(workspaceRepository, never()).findOpenByAssetIdForUpdate(any());
    }

    @Test
    void requiresReadyPassedHashedOwnerAccessibleBase() {
        CodeAsset asset = asset("asset-1", 7);
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));

        CodeVersion base = readyPassedBase("version-1", "asset-1", 7);
        base.setStatus("DEPRECATED");
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("version-1", "asset-1"))
                .thenReturn(Optional.of(base));
        when(authContext.canAccessObjectName(base.getStoragePath(), 7)).thenReturn(false);
        assertThrows(CodeAssetAccessException.class, () -> service.open("asset-1", "version-1"));

        when(authContext.canAccessObjectName(base.getStoragePath(), 7)).thenReturn(true);
        assertReason("BASE_VERSION_NOT_READY", () -> service.open("asset-1", "version-1"));

        base.setStatus("READY");
        base.setValidationStatus("FAILED");
        assertReason("BASE_VERSION_NOT_VALIDATED", () -> service.open("asset-1", "version-1"));

        base.setValidationStatus("PASSED");
        base.setArtifactSha256(" ");
        assertReason("BASE_VERSION_EVIDENCE_MISSING", () -> service.open("asset-1", "version-1"));

        base.setArtifactSha256("a".repeat(64));
        base.setOwnerUserId(8);
        assertThrows(CodeAssetAccessException.class, () -> service.open("asset-1", "version-1"));

        base.setOwnerUserId(7);
        when(authContext.canAccessObjectName(base.getStoragePath(), 7)).thenReturn(false);
        CodeAssetAccessException error = assertThrows(
                CodeAssetAccessException.class,
                () -> service.open("asset-1", "version-1")
        );
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains(base.getStoragePath()));
    }

    @Test
    void abandonChecksStatusThenRevisionAndIncrementsExactlyOnce() {
        CodeWorkspace workspace = workspace("workspace-1", "asset-1", 7, null);
        workspace.setRevision(4L);
        when(workspaceRepository.findAssetIdByIdAndDeletedFalse("workspace-1"))
                .thenReturn(Optional.of("asset-1"));
        when(workspaceRepository.findByIdAndDeletedFalseForUpdate("workspace-1"))
                .thenReturn(Optional.of(workspace));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset("asset-1", 7)));
        when(workspaceRepository.save(workspace)).thenReturn(workspace);

        CodeWorkspace abandoned = service.abandon("workspace-1", 4L);

        assertSame(workspace, abandoned);
        assertEquals(CodeWorkspace.STATUS_ABANDONED, abandoned.getStatus());
        assertEquals(5L, abandoned.getRevision());
        assertNotNull(abandoned.getClosedAt());
        verify(auditService).workspaceAbandoned("asset-1", "workspace-1", 5L);

        InOrder lockOrder = inOrder(workspaceRepository, assetRepository);
        lockOrder.verify(workspaceRepository).findAssetIdByIdAndDeletedFalse("workspace-1");
        lockOrder.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        lockOrder.verify(workspaceRepository).findByIdAndDeletedFalseForUpdate("workspace-1");

        CodeWorkspaceConflictException closed = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.abandon("workspace-1", 5L)
        );
        assertEquals("WORKSPACE_READ_ONLY", closed.getReasonCode());
    }

    private static void assertReason(String expected, Runnable action) {
        CodeValidationException error = assertThrows(CodeValidationException.class, action::run);
        assertEquals(expected, error.getReasonCode());
    }

    private static CodeAsset asset(String id, Integer owner) {
        CodeAsset asset = new CodeAsset();
        asset.setId(id);
        asset.setOwnerUserId(owner);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeWorkspace workspace(
            String id,
            String assetId,
            Integer owner,
            String baseVersionId
    ) {
        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId(id);
        workspace.setAssetId(assetId);
        workspace.setOwnerUserId(owner);
        workspace.setBaseVersionId(baseVersionId);
        workspace.setStatus(CodeWorkspace.STATUS_OPEN);
        workspace.setRevision(0L);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace.setDeleted(false);
        return workspace;
    }

    private static CodeVersion readyPassedBase(String id, String assetId, Integer owner) {
        CodeVersion version = new CodeVersion();
        version.setId(id);
        version.setAssetId(assetId);
        version.setOwnerUserId(owner);
        version.setStatus("READY");
        version.setValidationStatus("PASSED");
        version.setArtifactSha256("a".repeat(64));
        version.setStoragePath("users/7/codes/asset-1/base.zip");
        version.setSizeBytes(1024L);
        version.setDeleted(false);
        return version;
    }
}
