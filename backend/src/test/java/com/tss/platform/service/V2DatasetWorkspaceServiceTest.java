package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.dto.v2.V2DatasetEditability;
import com.tss.platform.dto.v2.V2DatasetPublishBlocker;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.dto.v2.V2DatasetVersionAllocationDto;
import com.tss.platform.dto.v2.V2DatasetWorkspaceCreateRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2DatasetWorkspaceServiceTest {

    @Test
    void readinessBlockersAreReturnedUnchangedByPublish() {
        Fixture fixture = new Fixture();
        V2DatasetPublishBlocker blocker = new V2DatasetPublishBlocker(
                "EMPTY_SAMPLE",
                "工作区存在空样本",
                "DATASET_SAMPLE",
                "sample-1"
        );
        V2DatasetPublishReadiness readiness =
                new V2DatasetPublishReadiness(
                        false,
                        3L,
                        List.of(blocker)
                );
        when(fixture.readinessService.evaluate(
                fixture.asset,
                fixture.workspace
        )).thenReturn(readiness);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.publish("workspace-1", 3L)
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        assertEquals("DATASET_NOT_PUBLISHABLE", error.getErrorCode());
        assertEquals(3L, error.getDetails().get("evaluatedRevision"));
        @SuppressWarnings("unchecked")
        List<V2DatasetPublishBlocker> returned =
                (List<V2DatasetPublishBlocker>) error.getDetails().get(
                        "blockers"
                );
        assertEquals(List.of(blocker), returned);
        assertSame(blocker, returned.get(0));
        verify(fixture.publishService, never()).publish(any());
    }

    @Test
    void sameRevisionReadyWorkspacePublishesWithVersionIdentity() {
        Fixture fixture = new Fixture();
        when(fixture.readinessService.evaluate(
                fixture.asset,
                fixture.workspace
        )).thenReturn(new V2DatasetPublishReadiness(true, 3L, List.of()));
        DatasetWorkspacePublishDto published =
                new DatasetWorkspacePublishDto();
        published.setDatasetAssetId("asset-1");
        published.setDatasetVersionId("workspace-1");
        published.setVersionNo(2);
        published.setVersionLabel("1.0.3");
        published.setStatus("READY");
        published.setPublishedAt(Instant.parse("2026-07-23T12:00:00Z"));
        when(fixture.publishService.publish("workspace-1"))
                .thenReturn(published);

        var result = fixture.service.publish("workspace-1", 3L);

        assertEquals("asset-1", result.getDatasetId());
        assertEquals("workspace-1", result.getCurrentVersion().getVersionId());
        assertEquals("1.0.3", result.getCurrentVersion().getVersionLabel());
        assertEquals(2, result.getCurrentVersion().getVersionNo());
        assertEquals("READY", result.getCurrentVersion().getStatus());
        verify(fixture.publishService).publish("workspace-1");
    }

    @Test
    void previewsDeletedVersionLabelReservationWithNextSuggestion() {
        Fixture fixture = new Fixture();
        when(fixture.workspaceService.previewVersionAllocation(
                "asset-1",
                "1.0.2"
        )).thenReturn(new DatasetWorkspaceService.VersionAllocationPreview(
                3,
                "v3",
                "1.0.2",
                false,
                DatasetWorkspaceService.DELETED_VERSION_RESERVED
        ));

        V2DatasetVersionAllocationDto result =
                fixture.service.versionAllocation("asset-1", "1.0.2");

        assertEquals(3, result.nextVersionNo());
        assertEquals("v3", result.defaultVersionLabel());
        assertEquals("1.0.2", result.requestedVersionLabel());
        assertEquals(false, result.requestedVersionLabelAvailable());
        assertEquals(
                DatasetWorkspaceService.DELETED_VERSION_RESERVED,
                result.unavailableReason()
        );
    }

    @Test
    void createsWorkspaceWithNormalizedCustomLabelWithoutLegacyUpdate() {
        Fixture fixture = new Fixture();
        DatasetWorkspaceDraftDto created = new DatasetWorkspaceDraftDto();
        created.setDraftVersionId("workspace-1");
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                " 1.0.3 "
        )).thenReturn("1.0.3");
        when(fixture.sourceInspector.inspect(
                fixture.asset,
                fixture.parent
        )).thenReturn(new V2DatasetEditability(true, List.of()));
        when(fixture.workspaceService.createDraft(
                "parent-1",
                "1.0.3"
        )).thenReturn(created);

        var result = fixture.service.create(
                "asset-1",
                new V2DatasetWorkspaceCreateRequest(" 1.0.3 ")
        );

        assertEquals("workspace-1", result.getWorkspaceId());
        assertEquals("1.0.3", result.getTargetVersion().getVersionLabel());
        assertEquals(2, result.getTargetVersion().getVersionNo());
        verify(fixture.workspaceService).createDraft(
                "parent-1",
                "1.0.3"
        );
    }

    @Test
    void createsWorkspaceFromExplicitHistoricalReadyBase() {
        Fixture fixture = new Fixture();
        DatasetVersion historical = new DatasetVersion();
        historical.setId("historical-1");
        historical.setAssetId("asset-1");
        historical.setVersion("v1");
        historical.setVersionLabel("v1");
        historical.setVersionNo(1);
        historical.setStatus("READY");
        historical.setDeleted(false);
        fixture.workspace.setParentVersionId("historical-1");
        fixture.workspace.setWorkspaceHeadVersionId("parent-1");
        DatasetWorkspaceDraftDto created = new DatasetWorkspaceDraftDto();
        created.setDraftVersionId("workspace-1");
        when(fixture.versionRepo.findByIdAndDeletedFalse("historical-1"))
                .thenReturn(Optional.of(historical));
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                " 1.0.3 "
        )).thenReturn("1.0.3");
        when(fixture.sourceInspector.inspect(
                fixture.asset,
                historical
        )).thenReturn(new V2DatasetEditability(true, List.of()));
        when(fixture.workspaceService.createDraft(
                "historical-1",
                "1.0.3"
        )).thenReturn(created);

        var result = fixture.service.create(
                "asset-1",
                new V2DatasetWorkspaceCreateRequest(
                        "historical-1",
                        " 1.0.3 "
                )
        );

        assertEquals("historical-1", result.getBaseVersion().getVersionId());
        verify(fixture.workspaceService).createDraft(
                "historical-1",
                "1.0.3"
        );
    }

    @Test
    void activeWorkspaceRetriesAreIdempotentForMissingOrMatchingLabel() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        "asset-1",
                        "DRAFT"
                )).thenReturn(Optional.of(fixture.workspace));
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                " 1.0.3 "
        )).thenReturn("1.0.3");

        var missingLabel = fixture.service.create("asset-1", null);
        var matchingLabel = fixture.service.create(
                "asset-1",
                new V2DatasetWorkspaceCreateRequest(" 1.0.3 ")
        );

        assertEquals(fixture.workspace.getId(), missingLabel.getWorkspaceId());
        assertEquals(fixture.workspace.getId(), matchingLabel.getWorkspaceId());
        verify(fixture.workspaceService, never()).createDraft(any(), any());
    }

    @Test
    void activeWorkspaceDifferentLabelReturnsStructuredConflict() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        "asset-1",
                        "DRAFT"
                )).thenReturn(Optional.of(fixture.workspace));
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                "1.0.4"
        )).thenReturn("1.0.4");
        when(fixture.workspaceService.previewVersionAllocation(
                "asset-1",
                "1.0.4"
        )).thenReturn(new DatasetWorkspaceService.VersionAllocationPreview(
                3,
                "v3",
                "1.0.4",
                true,
                null
        ));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest("1.0.4")
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(
                "DATASET_VERSION_LABEL_CONFLICT",
                error.getErrorCode()
        );
        assertEquals(
                "ACTIVE_WORKSPACE_LABEL_MISMATCH",
                error.getDetails().get("reasonCode")
        );
        assertEquals("1.0.4", error.getDetails().get("requestedVersionLabel"));
        assertEquals(3, error.getDetails().get("nextVersionNo"));
        assertEquals("v3", error.getDetails().get("defaultVersionLabel"));
        assertEquals("workspace-1", error.getDetails().get("workspaceId"));
        assertEquals("1.0.3", error.getDetails().get("currentVersionLabel"));
    }

    @Test
    void activeWorkspaceDifferentExplicitBaseReturnsStructuredConflict() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        "asset-1",
                        "DRAFT"
                )).thenReturn(Optional.of(fixture.workspace));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest(
                                "other-ready",
                                null
                        )
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("WORKSPACE_BASE_CONFLICT", error.getErrorCode());
        assertEquals("workspace-1", error.getDetails().get("workspaceId"));
        assertEquals(
                "parent-1",
                error.getDetails().get("activeBaseVersionId")
        );
        assertEquals(
                "other-ready",
                error.getDetails().get("requestedBaseVersionId")
        );
        verify(fixture.workspaceService, never()).createDraft(any(), any());
    }

    @Test
    void rejectsBlankOrNonReadyExplicitBase() {
        Fixture fixture = new Fixture();

        V2BusinessException blank = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest("   ", null)
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatus());
        assertEquals("INVALID_BASE_VERSION_ID", blank.getErrorCode());

        DatasetVersion draftBase = new DatasetVersion();
        draftBase.setId("draft-base");
        draftBase.setAssetId("asset-1");
        draftBase.setStatus("DRAFT");
        draftBase.setDeleted(false);
        when(fixture.versionRepo.findByIdAndDeletedFalse("draft-base"))
                .thenReturn(Optional.of(draftBase));

        V2BusinessException notReady = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest(
                                "draft-base",
                                null
                        )
                )
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notReady.getStatus());
        assertEquals("BASE_VERSION_NOT_READY", notReady.getErrorCode());
    }

    @Test
    void concurrentWorkspaceWinnerStillProducesLabelMismatchConflict() {
        Fixture fixture = new Fixture();
        DatasetWorkspaceDraftDto existing = new DatasetWorkspaceDraftDto();
        existing.setDraftVersionId("workspace-1");
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                "1.0.4"
        )).thenReturn("1.0.4");
        when(fixture.sourceInspector.inspect(
                fixture.asset,
                fixture.parent
        )).thenReturn(new V2DatasetEditability(true, List.of()));
        when(fixture.workspaceService.createDraft(
                "parent-1",
                "1.0.4"
        )).thenReturn(existing);
        when(fixture.workspaceService.previewVersionAllocation(
                "asset-1",
                "1.0.4"
        )).thenReturn(new DatasetWorkspaceService.VersionAllocationPreview(
                3,
                "v3",
                "1.0.4",
                true,
                null
        ));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest("1.0.4")
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(
                "DATASET_VERSION_LABEL_CONFLICT",
                error.getErrorCode()
        );
        assertEquals(
                "ACTIVE_WORKSPACE_LABEL_MISMATCH",
                error.getDetails().get("reasonCode")
        );
        assertEquals("workspace-1", error.getDetails().get("workspaceId"));
        assertEquals("1.0.3", error.getDetails().get("currentVersionLabel"));
    }

    @Test
    void reservedDeletedLabelReturnsStructuredConflict() {
        Fixture fixture = new Fixture();
        DatasetWorkspaceService.VersionAllocationPreview allocation =
                new DatasetWorkspaceService.VersionAllocationPreview(
                        3,
                        "v3",
                        "1.0.2",
                        false,
                        DatasetWorkspaceService.DELETED_VERSION_RESERVED
                );
        when(fixture.workspaceService.normalizeRequestedVersionLabel(
                "1.0.2"
        )).thenReturn("1.0.2");
        when(fixture.sourceInspector.inspect(
                fixture.asset,
                fixture.parent
        )).thenReturn(new V2DatasetEditability(true, List.of()));
        when(fixture.workspaceService.createDraft(
                "parent-1",
                "1.0.2"
        )).thenThrow(
                new DatasetWorkspaceService.VersionLabelConflictException(
                        allocation
                )
        );

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest("1.0.2")
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(
                "DATASET_VERSION_LABEL_CONFLICT",
                error.getErrorCode()
        );
        assertEquals(
                DatasetWorkspaceService.DELETED_VERSION_RESERVED,
                error.getDetails().get("reasonCode")
        );
        assertEquals("1.0.2", error.getDetails().get("requestedVersionLabel"));
        assertEquals(3, error.getDetails().get("nextVersionNo"));
        assertEquals("v3", error.getDetails().get("defaultVersionLabel"));
    }

    @Test
    void explicitBlankLabelReturnsInvalidVersionLabel() {
        Fixture fixture = new Fixture();
        when(fixture.workspaceService.normalizeRequestedVersionLabel("   "))
                .thenThrow(
                        new DatasetWorkspaceService.InvalidVersionLabelException()
                );

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.create(
                        "asset-1",
                        new V2DatasetWorkspaceCreateRequest("   ")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("INVALID_VERSION_LABEL", error.getErrorCode());
    }

    @Test
    void staleBaseIsReportedAsConflictInsteadOfGenericPublishBlocker() {
        Fixture fixture = new Fixture();
        V2DatasetPublishBlocker blocker = new V2DatasetPublishBlocker(
                "BASE_VERSION_STALE",
                "基线已变化"
        );
        when(fixture.readinessService.evaluate(
                fixture.asset,
                fixture.workspace
        )).thenReturn(new V2DatasetPublishReadiness(
                false,
                3L,
                List.of(blocker)
        ));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.publish("workspace-1", 3L)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("BASE_VERSION_STALE", error.getErrorCode());
        assertEquals(List.of(blocker), error.getDetails().get("blockers"));
    }

    @Test
    void activeWorkspaceExposesPersistedManifestFailureDetails() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        "asset-1",
                        "DRAFT"
                )).thenReturn(Optional.of(fixture.workspace));
        ImportJob failed = new ImportJob();
        failed.setId("job-1");
        failed.setStatus("FAILED");
        failed.setErrorCode("INVALID_MANIFEST");
        failed.setErrorMessage("Manifest 内容无效，请检查后重试");
        failed.setErrorDetailsJson(
                "{\"field\":\"samples[0].data[0].path\","
                        + "\"path\":\"missing.png\","
                        + "\"reason\":\"path not found in zip\"}"
        );
        when(fixture.importJobRepo.findByDatasetVersionId("workspace-1"))
                .thenReturn(List.of(failed));

        var result = fixture.service.create("asset-1", null);

        assertEquals("INVALID_MANIFEST", result.getUserError().getErrorCode());
        assertEquals(
                "samples[0].data[0].path",
                result.getUserError().getDetails().get("field")
        );
        assertEquals(
                "missing.png",
                result.getUserError().getDetails().get("path")
        );
    }

    @Test
    void abandonIsIdempotentAndCleansOnlyWorkspaceOwnedObjects() {
        Fixture fixture = new Fixture();
        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setId("upload-1");
        upload.setStatus("UPLOADING");
        upload.setOwnerUserId(7);
        DatasetUploadChunk chunk = new DatasetUploadChunk();
        chunk.setUploadId("upload-1");
        chunk.setPartIndex(0);
        chunk.setObjectName("chunks/upload-1/0");
        ImportJob job = new ImportJob();
        job.setId("job-1");
        job.setStatus("RUNNING");

        DatasetVersionPackage inherited =
                relation("workspace-1", "package-parent", "PRIMARY", 0);
        DatasetVersionPackage overlay =
                relation("workspace-1", "package-overlay", "OVERLAY", 1);
        DatasetVersionPackage parentRelation =
                relation("parent-1", "package-parent", "PRIMARY", 0);
        DatasetPackage overlayPackage = new DatasetPackage();
        overlayPackage.setId("package-overlay");
        overlayPackage.setDatasetAssetId("asset-1");
        overlayPackage.setStoragePath(
                "users/7/datasets/asset-1/workspaces/workspace-1/overlay.bin"
        );
        overlayPackage.setStatus("READY");
        overlayPackage.setDeleted(false);

        when(fixture.uploadSessionRepo.findByVersionIdForUpdate("workspace-1"))
                .thenReturn(List.of(upload));
        when(fixture.uploadChunkRepo
                .findByUploadIdOrderByPartIndexAsc("upload-1"))
                .thenReturn(List.of(chunk));
        when(fixture.importJobRepo
                .findByDatasetVersionIdForUpdate("workspace-1"))
                .thenReturn(List.of(job));
        when(fixture.versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc("parent-1"))
                .thenReturn(List.of(parentRelation));
        when(fixture.versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc("workspace-1"))
                .thenReturn(List.of(inherited, overlay));
        when(fixture.versionPackageRepo.findByPackageId("package-overlay"))
                .thenReturn(List.of());
        when(fixture.packageRepo.findByIdAndDeletedFalse("package-overlay"))
                .thenReturn(Optional.of(overlayPackage));

        var first = fixture.service.abandon("workspace-1", 3L);
        var replay = fixture.service.abandon("workspace-1", 4L);

        assertEquals("ABANDONED", first.status());
        assertEquals("ABANDONED", replay.status());
        assertEquals(4L, first.workspaceRevision());
        assertEquals(4L, replay.workspaceRevision());
        assertTrue(fixture.workspace.getDeleted());
        assertNotNull(fixture.workspace.getDeletedAt());
        assertEquals("DISCARDED", upload.getStatus());
        assertEquals("SUPERSEDED", job.getStatus());
        assertEquals("WORKSPACE_ABANDONED", job.getErrorCode());
        assertTrue(overlayPackage.getDeleted());
        assertEquals("SUPERSEDED", overlayPackage.getStatus());
        verify(fixture.uploadChunkRepo).deleteByUploadId("upload-1");
        verify(fixture.deleteTaskService).enqueueDefaultBucketDelete(
                "chunks/upload-1/0",
                MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                "upload-1",
                7
        );
        verify(fixture.deleteTaskService).enqueueDefaultBucketDelete(
                overlayPackage.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                "package-overlay",
                7
        );
        verify(fixture.packageRepo, never())
                .findByIdAndDeletedFalse("package-parent");
        verify(fixture.uploadSessionRepo, times(1))
                .findByVersionIdForUpdate("workspace-1");
        verify(fixture.auditService, times(1)).recordUserAction(
                eq(fixture.asset),
                eq(fixture.workspace),
                eq("WORKSPACE_ABANDONED"),
                eq("DATASET_VERSION"),
                eq("workspace-1"),
                isNull(),
                isNull(),
                anyMap()
        );
    }

    private static DatasetVersionPackage relation(
            String versionId,
            String packageId,
            String role,
            int order
    ) {
        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId(versionId);
        relation.setPackageId(packageId);
        relation.setPackageRole(role);
        relation.setPackageOrder(order);
        return relation;
    }

    private static final class Fixture {
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetUploadSessionRepository uploadSessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository uploadChunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final ImportJobRepository importJobRepo =
                mock(ImportJobRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceService workspaceService =
                mock(DatasetWorkspaceService.class);
        private final DatasetWorkspacePublishService publishService =
                mock(DatasetWorkspacePublishService.class);
        private final DatasetWorkspaceCommandService commandService =
                mock(DatasetWorkspaceCommandService.class);
        private final DatasetWorkspaceReadinessService readinessService =
                mock(DatasetWorkspaceReadinessService.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final DatasetWorkspaceSourceInspector sourceInspector =
                mock(DatasetWorkspaceSourceInspector.class);
        private final DatasetAsset asset = asset();
        private final DatasetVersion parent = parent();
        private final DatasetVersion workspace = workspace();
        private final V2DatasetWorkspaceService service =
                new V2DatasetWorkspaceService(
                        assetRepo,
                        versionRepo,
                        sampleRepo,
                        uploadSessionRepo,
                        uploadChunkRepo,
                        importJobRepo,
                        versionPackageRepo,
                        packageRepo,
                        authContext,
                        workspaceService,
                        publishService,
                        commandService,
                        readinessService,
                        auditService,
                        deleteTaskService,
                        sourceInspector
                );

        private Fixture() {
            DatasetWorkspaceCommandService.WorkspaceAccess access =
                    new DatasetWorkspaceCommandService.WorkspaceAccess(
                            asset,
                            workspace
                    );
            when(versionRepo.findByIdAndDeletedFalse("workspace-1"))
                    .thenReturn(Optional.of(workspace));
            when(versionRepo.findByIdAndDeletedFalse("parent-1"))
                    .thenReturn(Optional.of(parent));
            when(assetRepo.findByIdAndDeletedFalse("asset-1"))
                    .thenReturn(Optional.of(asset));
            when(assetRepo.findByIdAndDeletedFalseForUpdate("asset-1"))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(7)).thenReturn(true);
            when(readinessService.evaluate(asset, workspace))
                    .thenReturn(new V2DatasetPublishReadiness(
                            false,
                            3L,
                            List.of()
                    ));
            when(uploadSessionRepo.findByVersionId("workspace-1"))
                    .thenReturn(List.of());
            when(importJobRepo.findByDatasetVersionId("workspace-1"))
                    .thenReturn(List.of());
            when(commandService.lockForMutation("workspace-1", 3L))
                    .thenReturn(access);
            when(commandService.lockForAbandon(
                    eq("workspace-1"),
                    any()
            )).thenReturn(access);
            when(commandService.incrementRevision(workspace))
                    .thenAnswer(invocation -> {
                        long next = workspace.getWorkspaceRevision() + 1L;
                        workspace.setWorkspaceRevision(next);
                        workspace.setUpdatedAt(Instant.now());
                        return next;
                    });
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            asset.setCurrentVersionId("parent-1");
            asset.setDeleted(false);
            return asset;
        }

        private static DatasetVersion workspace() {
            DatasetVersion workspace = new DatasetVersion();
            workspace.setId("workspace-1");
            workspace.setAssetId("asset-1");
            workspace.setParentVersionId("parent-1");
            workspace.setWorkspaceHeadVersionId("parent-1");
            workspace.setVersion("1.0.3");
            workspace.setVersionLabel("1.0.3");
            workspace.setVersionNo(2);
            workspace.setStatus("DRAFT");
            workspace.setWorkspaceRevision(3L);
            workspace.setDeleted(false);
            return workspace;
        }

        private static DatasetVersion parent() {
            DatasetVersion parent = new DatasetVersion();
            parent.setId("parent-1");
            parent.setAssetId("asset-1");
            parent.setVersionNo(1);
            parent.setStatus("READY");
            parent.setStoragePath(
                    "users/7/datasets/asset-1/v1/parent.zip"
            );
            parent.setDeleted(false);
            return parent;
        }
    }
}
