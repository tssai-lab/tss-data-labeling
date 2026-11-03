package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceServiceTest {

    @Test
    void createsMaterializedDraftFromReadyWithoutChangingCurrentVersion() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(fixture.asset.getId(), "v3"))
                .thenReturn(false);
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatasetWorkspaceDraftDto result =
                fixture.service.createDraft(fixture.parent.getId());

        assertEquals(fixture.parent.getId(), result.getParentVersionId());
        assertEquals(fixture.asset.getId(), result.getDatasetAssetId());
        assertEquals(3, result.getVersionNo());
        assertEquals("DRAFT", result.getStatus());
        assertEquals(fixture.parent.getId(), result.getCurrentVersionId());
        assertEquals(fixture.parent.getId(), fixture.asset.getCurrentVersionId());

        ArgumentCaptor<DatasetVersion> captor = ArgumentCaptor.forClass(DatasetVersion.class);
        verify(fixture.versionRepo).saveAndFlush(captor.capture());
        DatasetVersion draft = captor.getValue();
        assertEquals(fixture.parent.getId(), draft.getParentVersionId());
        assertEquals(fixture.asset.getId(), draft.getAssetId());
        assertEquals(3, draft.getVersionNo());
        assertEquals("v3", draft.getVersion());
        assertEquals("v3", draft.getVersionLabel());
        assertEquals("DRAFT", draft.getStatus());
        assertEquals(
                fixture.parent.getId(),
                draft.getWorkspaceHeadVersionId()
        );
        assertEquals(fixture.parent.getStoragePath(), draft.getStoragePath());
        assertEquals(fixture.parent.getFileName(), draft.getFileName());
        assertEquals(fixture.parent.getSizeBytes(), draft.getSizeBytes());
        assertEquals(fixture.parent.getFileFingerprint(), draft.getFileFingerprint());
        assertEquals(fixture.parent.getCvTaskType(), draft.getCvTaskType());
        assertEquals(fixture.parent.getAnnotationFormat(), draft.getAnnotationFormat());
        assertNull(draft.getPublishedAt());
        assertFalse(Boolean.TRUE.equals(draft.getDeleted()));
        verify(fixture.assetRepo, never()).save(any(DatasetAsset.class));
        verify(fixture.materializer).materialize(fixture.asset, fixture.parent, draft);
        verify(fixture.auditService).recordDraftCreated(fixture.asset, draft);
    }

    @Test
    void createsDraftWithNormalizedCustomVersionLabelAtomically() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v3"
        )).thenReturn(false);
        when(fixture.versionRepo.findByAssetIdAndVersion(
                fixture.asset.getId(),
                "1.0.3"
        )).thenReturn(Optional.empty());
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatasetWorkspaceDraftDto result = fixture.service.createDraft(
                fixture.parent.getId(),
                " 1.0.3 "
        );

        assertEquals(3, result.getVersionNo());
        ArgumentCaptor<DatasetVersion> captor =
                ArgumentCaptor.forClass(DatasetVersion.class);
        verify(fixture.versionRepo).saveAndFlush(captor.capture());
        assertEquals(3, captor.getValue().getVersionNo());
        assertEquals("1.0.3", captor.getValue().getVersion());
        assertEquals("1.0.3", captor.getValue().getVersionLabel());
    }

    @Test
    void previewsDeletedLabelReservationAndNextDefaultLabel() {
        Fixture fixture = new Fixture();
        DatasetVersion deleted = new DatasetVersion();
        deleted.setAssetId(fixture.asset.getId());
        deleted.setVersion("1.0.2");
        deleted.setVersionLabel("1.0.2");
        deleted.setVersionNo(2);
        deleted.setDeleted(true);
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v3"
        )).thenReturn(false);
        when(fixture.versionRepo.findByAssetIdAndVersion(
                fixture.asset.getId(),
                "1.0.2"
        )).thenReturn(Optional.of(deleted));

        DatasetWorkspaceService.VersionAllocationPreview preview =
                fixture.service.previewVersionAllocation(
                        fixture.asset.getId(),
                        " 1.0.2 "
                );

        assertEquals(3, preview.nextVersionNo());
        assertEquals("v3", preview.defaultVersionLabel());
        assertEquals("1.0.2", preview.requestedVersionLabel());
        assertFalse(preview.requestedVersionLabelAvailable());
        assertEquals(
                DatasetWorkspaceService.DELETED_VERSION_RESERVED,
                preview.unavailableReason()
        );
    }

    @Test
    void skipsReservedDefaultLabelWhenAllocatingNextInternalNumber() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v3"
        )).thenReturn(true);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v4"
        )).thenReturn(false);

        DatasetWorkspaceService.VersionAllocationPreview preview =
                fixture.service.previewVersionAllocation(
                        fixture.asset.getId(),
                        null
                );

        assertEquals(4, preview.nextVersionNo());
        assertEquals("v4", preview.defaultVersionLabel());
        assertNull(preview.requestedVersionLabel());
        assertNull(preview.requestedVersionLabelAvailable());
        assertNull(preview.unavailableReason());
    }

    @Test
    void rejectsReservedDeletedLabelBeforeSavingDraft() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        DatasetVersion deleted = new DatasetVersion();
        deleted.setAssetId(fixture.asset.getId());
        deleted.setVersion("1.0.2");
        deleted.setVersionNo(2);
        deleted.setDeleted(true);
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v3"
        )).thenReturn(false);
        when(fixture.versionRepo.findByAssetIdAndVersion(
                fixture.asset.getId(),
                "1.0.2"
        )).thenReturn(Optional.of(deleted));

        DatasetWorkspaceService.VersionLabelConflictException error =
                assertThrows(
                        DatasetWorkspaceService.VersionLabelConflictException.class,
                        () -> fixture.service.createDraft(
                                fixture.parent.getId(),
                                "1.0.2"
                        )
                );

        assertEquals(
                DatasetWorkspaceService.DELETED_VERSION_RESERVED,
                error.getAllocation().unavailableReason()
        );
        verify(fixture.versionRepo, never())
                .saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void mapsConcurrentVersionLabelConstraintToTypedConflict() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(
                fixture.asset.getId(),
                "v3"
        )).thenReturn(false);
        when(fixture.versionRepo.findByAssetIdAndVersion(
                fixture.asset.getId(),
                "1.0.3"
        )).thenReturn(Optional.empty());
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key violates uk_dataset_version_asset_version"
                ));

        DatasetWorkspaceService.VersionLabelConflictException error =
                assertThrows(
                        DatasetWorkspaceService.VersionLabelConflictException.class,
                        () -> fixture.service.createDraft(
                                fixture.parent.getId(),
                                "1.0.3"
                        )
                );

        assertEquals(
                DatasetWorkspaceService.ACTIVE_VERSION_EXISTS,
                error.getAllocation().unavailableReason()
        );
        assertEquals("1.0.3", error.getAllocation().requestedVersionLabel());
    }

    @Test
    void rejectsExplicitBlankAndOverlongVersionLabels() {
        Fixture fixture = new Fixture();

        assertThrows(
                DatasetWorkspaceService.InvalidVersionLabelException.class,
                () -> fixture.service.normalizeRequestedVersionLabel("   ")
        );
        assertThrows(
                DatasetWorkspaceService.InvalidVersionLabelException.class,
                () -> fixture.service.normalizeRequestedVersionLabel(
                        "x".repeat(65)
                )
        );
        assertNull(fixture.service.normalizeRequestedVersionLabel(null));
        assertEquals(
                "release",
                fixture.service.normalizeRequestedVersionLabel(" release ")
        );
    }

    @Test
    void propagatesMaterializationFailureSoDraftTransactionCanRollBack() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(fixture.asset.getId(), "v3"))
                .thenReturn(false);
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("copy failed"))
                .when(fixture.materializer)
                .materialize(
                        eq(fixture.asset),
                        eq(fixture.parent),
                        any(DatasetVersion.class)
                );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.createDraft(fixture.parent.getId())
        );

        assertEquals("copy failed", error.getMessage());
        verify(fixture.assetRepo, never()).save(any(DatasetAsset.class));
        verify(fixture.auditService, never())
                .recordDraftCreated(eq(fixture.asset), any(DatasetVersion.class));
    }

    @Test
    void allowsPackageBackedParentWithoutLegacyVersionStoragePath() {
        Fixture fixture = new Fixture();
        fixture.parent.setStoragePath(null);
        fixture.stubOwnedReady();
        when(fixture.versionRepo.findMaxVersionNoByAssetId(fixture.asset.getId()))
                .thenReturn(2);
        when(fixture.versionRepo.existsByAssetIdAndVersion(fixture.asset.getId(), "v3"))
                .thenReturn(false);
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatasetWorkspaceDraftDto result =
                fixture.service.createDraft(fixture.parent.getId());

        assertEquals("DRAFT", result.getStatus());
        ArgumentCaptor<DatasetVersion> draftCaptor =
                ArgumentCaptor.forClass(DatasetVersion.class);
        verify(fixture.materializer).materialize(
                eq(fixture.asset),
                eq(fixture.parent),
                draftCaptor.capture()
        );
        assertNull(draftCaptor.getValue().getStoragePath());
    }

    @Test
    void rejectsDraftDeprecatedAndArchivedSources() {
        for (String status : new String[]{"DRAFT", "DEPRECATED", "ARCHIVED"}) {
            Fixture fixture = new Fixture();
            fixture.parent.setStatus(status);
            fixture.stubOwnedParent();

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.createDraft(fixture.parent.getId())
            );

            assertEquals(
                    "dataset version must be READY: " + fixture.parent.getId()
                            + ", status=" + status,
                    error.getMessage()
            );
            verify(fixture.versionRepo, never())
                    .saveAndFlush(any(DatasetVersion.class));
        }
    }

    @Test
    void rejectsDeletedSource() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo.findByIdAndDeletedFalse(fixture.parent.getId()))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.createDraft(fixture.parent.getId())
        );

        assertEquals("dataset version not found or no permission", error.getMessage());
        verify(fixture.versionRepo, never())
                .saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void rejectsExistingDraftWithExplicitVersionId() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        DatasetVersion existingDraft = new DatasetVersion();
        existingDraft.setId("draft-existing");
        existingDraft.setStatus("DRAFT");
        when(fixture.versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                fixture.asset.getId(),
                "DRAFT"
        )).thenReturn(Optional.of(existingDraft));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.createDraft(fixture.parent.getId())
        );

        assertEquals(
                "dataset asset already has an active DRAFT version: draft-existing",
                error.getMessage()
        );
        verify(fixture.versionRepo, never())
                .saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void v2CreateContinuesDraftThatAppearsAfterItsInitialRead() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedReady();
        DatasetVersion existingDraft = new DatasetVersion();
        existingDraft.setId("draft-existing");
        existingDraft.setAssetId(fixture.asset.getId());
        existingDraft.setParentVersionId(fixture.parent.getId());
        existingDraft.setVersionNo(3);
        existingDraft.setVersion("1.0.3");
        existingDraft.setVersionLabel("1.0.3");
        existingDraft.setStatus("DRAFT");
        when(fixture.versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        fixture.asset.getId(),
                        "DRAFT"
                )).thenReturn(Optional.of(existingDraft));

        DatasetWorkspaceDraftDto result = fixture.service.createDraft(
                fixture.parent.getId(),
                "1.0.3"
        );

        assertEquals("draft-existing", result.getDraftVersionId());
        assertEquals(3, result.getVersionNo());
        assertEquals("workspace draft already exists", result.getMessage());
        verify(fixture.versionRepo, never())
                .saveAndFlush(any(DatasetVersion.class));
        verify(fixture.materializer, never()).materialize(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsOtherUsersVersionWithoutRevealingStatus() {
        Fixture fixture = new Fixture();
        fixture.parent.setStatus("DRAFT");
        when(fixture.versionRepo.findByIdAndDeletedFalse(fixture.parent.getId()))
                .thenReturn(Optional.of(fixture.parent));
        when(fixture.versionRepo.findByIdAndDeletedFalseForUpdate(fixture.parent.getId()))
                .thenReturn(Optional.of(fixture.parent));
        when(fixture.assetRepo.findByIdAndDeletedFalseForUpdate(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.createDraft(fixture.parent.getId())
        );

        assertEquals("dataset version not found or no permission", error.getMessage());
        verify(fixture.versionRepo, never())
                .saveAndFlush(any(DatasetVersion.class));
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetVersionLifecycleService lifecycle =
                new DatasetVersionLifecycleService(versionRepo);
        private final DatasetWorkspaceMaterializer materializer =
                mock(DatasetWorkspaceMaterializer.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final DatasetWorkspaceService service =
                new DatasetWorkspaceService(
                        versionRepo,
                        assetRepo,
                        authContext,
                        lifecycle,
                        materializer,
                        auditService
                );
        private final DatasetAsset asset = asset();
        private final DatasetVersion parent = parent();

        private void stubOwnedReady() {
            stubOwnedParent();
            when(versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                    asset.getId(),
                    "DRAFT"
            )).thenReturn(Optional.empty());
        }

        private void stubOwnedParent() {
            when(versionRepo.findByIdAndDeletedFalse(parent.getId()))
                    .thenReturn(Optional.of(parent));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(parent.getId()))
                    .thenReturn(Optional.of(parent));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
            when(authContext.currentUserId()).thenReturn(7);
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            asset.setCurrentVersionId("ready-2");
            asset.setDeleted(false);
            return asset;
        }

        private static DatasetVersion parent() {
            DatasetVersion version = new DatasetVersion();
            version.setId("ready-2");
            version.setAssetId("asset-1");
            version.setVersion("v2");
            version.setVersionLabel("v2");
            version.setVersionNo(2);
            version.setStatus("READY");
            version.setStoragePath("users/7/datasets/asset-1/v2/data.zip");
            version.setFileName("data.zip");
            version.setSizeBytes(1000L);
            version.setFileFingerprint("sha256:abc");
            version.setCvTaskType("CLASSIFICATION");
            version.setAnnotationFormat("COCO");
            version.setOwnerUserId(7);
            version.setDeleted(false);
            return version;
        }
    }
}
