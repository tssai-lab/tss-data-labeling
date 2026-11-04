package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspacePublishServiceTest {

    @Test
    void publishesValidDraftAndAdvancesCurrentVersionWithoutChangingParent() {
        Fixture fixture = new Fixture();
        fixture.stubValidDraft();
        String parentStatus = fixture.parent.getStatus();
        Instant parentPublishedAt = fixture.parent.getPublishedAt();

        DatasetWorkspacePublishDto result =
                fixture.service.publish(fixture.draft.getId());

        assertEquals("READY", fixture.draft.getStatus());
        assertNotNull(fixture.draft.getPublishedAt());
        assertEquals(fixture.draft.getId(), fixture.asset.getCurrentVersionId());
        assertEquals("READY", result.getStatus());
        assertEquals(fixture.draft.getId(), result.getCurrentVersionId());
        assertEquals("1.0.3", result.getVersionLabel());
        assertEquals(parentStatus, fixture.parent.getStatus());
        assertEquals(parentPublishedAt, fixture.parent.getPublishedAt());
        verify(fixture.versionRepo).saveAndFlush(fixture.draft);
        verify(fixture.assetRepo).saveAndFlush(fixture.asset);
        verify(fixture.importJobRepo, never()).save(any());
        verify(fixture.packageRepo, never()).save(any());
        verify(fixture.versionPackageRepo, never()).save(any());
        verify(fixture.sampleRepo, never()).save(any());
        verify(fixture.auditService).recordVersionPublished(
                fixture.asset,
                fixture.draft
        );
    }

    @Test
    void readinessIsTheOnlyBusinessValidationSourceOnTheProductionPath() {
        Fixture fixture = new Fixture();
        fixture.stubLockedVersionAndAsset();
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(true);
        when(fixture.versionRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.assetRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DatasetWorkspaceReadinessService readinessService =
                mock(DatasetWorkspaceReadinessService.class);
        when(readinessService.evaluate(fixture.asset, fixture.draft))
                .thenReturn(new V2DatasetPublishReadiness(
                        true,
                        0L,
                        List.of()
                ));
        DatasetWorkspaceRawStorageService rawStorageService =
                mock(DatasetWorkspaceRawStorageService.class);
        DatasetWorkspacePublishService service =
                new DatasetWorkspacePublishService(
                        fixture.versionRepo,
                        fixture.assetRepo,
                        fixture.importJobRepo,
                        fixture.versionPackageRepo,
                        fixture.packageRepo,
                        fixture.sampleRepo,
                        fixture.dataRepo,
                        fixture.annotationRepo,
                        fixture.authContext,
                        fixture.auditService,
                        readinessService,
                        rawStorageService
                );

        DatasetWorkspacePublishDto result = service.publish(fixture.draft.getId());

        assertEquals("READY", result.getStatus());
        verify(readinessService).evaluate(fixture.asset, fixture.draft);
        verify(fixture.importJobRepo, never())
                .findByDatasetVersionId(fixture.draft.getId());
        verify(fixture.packageRepo, never()).findAllById(any());
        verify(fixture.sampleRepo, never())
                .findDuplicateExternalIdsByDatasetVersionId(fixture.draft.getId());
    }

    @Test
    void locksAssetBeforeDraftAndRejectsCurrentVersionRollback() {
        Fixture fixture = new Fixture();
        fixture.stubValidDraft();

        fixture.service.publish(fixture.draft.getId());

        InOrder lockOrder = inOrder(fixture.assetRepo, fixture.versionRepo);
        lockOrder.verify(fixture.assetRepo)
                .findByIdAndDeletedFalseForUpdate(fixture.asset.getId());
        lockOrder.verify(fixture.versionRepo)
                .findByIdAndDeletedFalseForUpdate(fixture.draft.getId());

        Fixture rollback = new Fixture();
        rollback.draft.setVersionNo(2);
        rollback.stubValidDraft();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> rollback.service.publish(rollback.draft.getId())
        );

        assertEquals(
                "draft version must be newer than current READY version",
                error.getMessage()
        );
        assertEquals("DRAFT", rollback.draft.getStatus());
        assertEquals(rollback.parent.getId(), rollback.asset.getCurrentVersionId());
        verify(rollback.versionRepo, never()).saveAndFlush(any());
        verify(rollback.assetRepo, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPendingRunningFailedAndPartialImportJobs() {
        for (String status : List.of("PENDING", "RUNNING", "FAILED", "PARTIAL")) {
            Fixture fixture = new Fixture();
            fixture.stubValidDraft();
            ImportJob job = new ImportJob();
            job.setId("job-" + status.toLowerCase());
            job.setDatasetVersionId(fixture.draft.getId());
            job.setStatus(status);
            when(fixture.importJobRepo.findByDatasetVersionId(fixture.draft.getId()))
                    .thenReturn(List.of(job));

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.publish(fixture.draft.getId())
            );

            assertEquals(
                    "dataset workspace has non-success ImportJob: " + status,
                    error.getMessage()
            );
            fixture.verifyNotPublished();
        }
    }

    @Test
    void publishesWhenOldFailedAppendPackageWasSuperseded() {
        Fixture fixture = new Fixture();
        fixture.stubValidDraft();

        DatasetPackage supersededPackage = new DatasetPackage();
        supersededPackage.setId("package-old-failed");
        supersededPackage.setDatasetAssetId(fixture.asset.getId());
        supersededPackage.setStoragePath("users/7/datasets/asset-1/v2/old-failed.zip");
        supersededPackage.setFileName("old-failed.zip");
        supersededPackage.setSizeBytes(100L);
        supersededPackage.setStatus("SUPERSEDED");
        supersededPackage.setCreatedAt(Instant.parse("2026-06-01T00:01:00Z"));
        supersededPackage.setDeleted(false);

        DatasetVersionPackage supersededRelation = new DatasetVersionPackage();
        supersededRelation.setDatasetVersionId(fixture.draft.getId());
        supersededRelation.setPackageId(supersededPackage.getId());
        supersededRelation.setPackageRole("APPEND");
        supersededRelation.setPackageOrder(1);
        supersededRelation.setCreatedAt(supersededPackage.getCreatedAt());

        ImportJob supersededJob = new ImportJob();
        supersededJob.setId("job-old-failed");
        supersededJob.setDatasetVersionId(fixture.draft.getId());
        supersededJob.setPackageId(supersededPackage.getId());
        supersededJob.setStatus("SUPERSEDED");

        when(fixture.importJobRepo.findByDatasetVersionId(fixture.draft.getId()))
                .thenReturn(List.of(supersededJob));
        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.draft.getId()
        )).thenReturn(List.of(fixture.relation, supersededRelation));
        when(fixture.packageRepo.findAllById(any()))
                .thenReturn(List.of(fixture.datasetPackage, supersededPackage));

        DatasetWorkspacePublishDto result =
                fixture.service.publish(fixture.draft.getId());

        assertEquals("READY", result.getStatus());
        assertEquals(fixture.draft.getId(), fixture.asset.getCurrentVersionId());
    }

    @Test
    void rejectsNonReadyPackagesIncludingFailedAndPartialPackages() {
        for (String status : List.of("PENDING", "IMPORTING", "FAILED", "PARTIAL")) {
            Fixture fixture = new Fixture();
            fixture.datasetPackage.setStatus(status);
            fixture.stubValidDraft();

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.publish(fixture.draft.getId())
            );

            assertEquals(
                    "dataset workspace package is not READY: "
                            + fixture.datasetPackage.getId() + ", status=" + status,
                    error.getMessage()
            );
            fixture.verifyNotPublished();
        }
    }

    @Test
    void rejectsSuccessfulImportJobWhosePackageIsNotLinkedToDraft() {
        Fixture fixture = new Fixture();
        fixture.stubValidDraft();
        ImportJob job = new ImportJob();
        job.setId("job-success");
        job.setDatasetVersionId(fixture.draft.getId());
        job.setPackageId("missing-package");
        job.setStatus("SUCCESS");
        when(fixture.importJobRepo.findByDatasetVersionId(fixture.draft.getId()))
                .thenReturn(List.of(job));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.publish(fixture.draft.getId())
        );

        assertEquals(
                "dataset workspace ImportJob references unlinked package: missing-package",
                error.getMessage()
        );
        fixture.verifyNotPublished();
    }

    @Test
    void rejectsEmptyDraftAndDuplicateActiveSampleKeys() {
        Fixture empty = new Fixture();
        empty.stubValidDraft();
        when(empty.sampleRepo.countByDatasetVersionIdAndDeletedFalse(
                empty.draft.getId()
        )).thenReturn(0L);

        assertEquals(
                "dataset workspace must contain at least one undeleted sample",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> empty.service.publish(empty.draft.getId())
                ).getMessage()
        );

        Fixture duplicateExternal = new Fixture();
        duplicateExternal.stubValidDraft();
        when(duplicateExternal.sampleRepo
                .findDuplicateExternalIdsByDatasetVersionId(
                        duplicateExternal.draft.getId()
                )).thenReturn(List.of("scene-1"));
        assertEquals(
                "duplicate externalId in dataset workspace: scene-1",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> duplicateExternal.service.publish(
                                duplicateExternal.draft.getId()
                        )
                ).getMessage()
        );

        Fixture duplicateIndex = new Fixture();
        duplicateIndex.stubValidDraft();
        when(duplicateIndex.sampleRepo
                .findDuplicateSampleIndexesByDatasetVersionId(
                        duplicateIndex.draft.getId()
                )).thenReturn(List.of(1));
        assertEquals(
                "duplicate sampleIndex in dataset workspace: 1",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> duplicateIndex.service.publish(
                                duplicateIndex.draft.getId()
                        )
                ).getMessage()
        );
    }

    @Test
    void rejectsIncompletePackageRelationsAndUnresolvedMetadataReferences() {
        Fixture missingRelation = new Fixture();
        missingRelation.stubValidDraft();
        when(missingRelation.versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc(
                        missingRelation.draft.getId()
                )).thenReturn(List.of());
        assertEquals(
                "dataset workspace has no package relation",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> missingRelation.service.publish(
                                missingRelation.draft.getId()
                        )
                ).getMessage()
        );

        Fixture missingReference = new Fixture();
        missingReference.stubValidDraft();
        when(missingReference.dataRepo
                .findDistinctPackageIdsByDatasetVersionId(
                        missingReference.draft.getId()
                )).thenReturn(List.of("missing-package"));
        assertEquals(
                "dataset workspace metadata references unlinked package: missing-package",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> missingReference.service.publish(
                                missingReference.draft.getId()
                        )
                ).getMessage()
        );
    }

    @Test
    void rejectsNullPackageReferences() {
        Fixture fixture = new Fixture();
        fixture.stubValidDraft();
        when(fixture.annotationRepo
                .countByDatasetVersionIdAndPackageIdIsNull(fixture.draft.getId()))
                .thenReturn(1L);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.publish(fixture.draft.getId())
        );

        assertEquals(
                "dataset workspace annotation packageId is missing",
                error.getMessage()
        );
        fixture.verifyNotPublished();
    }

    @Test
    void rejectsNonDraftAndUnauthorizedVersions() {
        Fixture ready = new Fixture();
        ready.draft.setStatus("READY");
        ready.stubLockedVersionAndAsset();
        when(ready.authContext.canAccessOwner(ready.asset.getOwnerUserId()))
                .thenReturn(true);
        assertEquals(
                "dataset version must be DRAFT",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ready.service.publish(ready.draft.getId())
                ).getMessage()
        );

        Fixture unauthorized = new Fixture();
        unauthorized.stubLockedVersionAndAsset();
        when(unauthorized.authContext.canAccessOwner(
                unauthorized.asset.getOwnerUserId()
        )).thenReturn(false);
        assertEquals(
                "dataset workspace version not found or no permission",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> unauthorized.service.publish(
                                unauthorized.draft.getId()
                        )
                ).getMessage()
        );
        unauthorized.verifyNotPublished();
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final ImportJobRepository importJobRepo =
                mock(ImportJobRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final DatasetAsset asset = asset();
        private final DatasetVersion parent = parent();
        private final DatasetVersion draft = draft();
        private final DatasetPackage datasetPackage = datasetPackage();
        private final DatasetVersionPackage relation = relation();
        private final DatasetWorkspacePublishService service =
                new DatasetWorkspacePublishService(
                        versionRepo,
                        assetRepo,
                        importJobRepo,
                        versionPackageRepo,
                        packageRepo,
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        authContext,
                        auditService
                );

        private void stubValidDraft() {
            stubLockedVersionAndAsset();
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
            when(versionRepo.findByIdAndDeletedFalse(parent.getId()))
                    .thenReturn(Optional.of(parent));
            when(importJobRepo.findByDatasetVersionId(draft.getId()))
                    .thenReturn(List.of());
            when(versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                    draft.getId()
            )).thenReturn(List.of(relation));
            when(packageRepo.findAllById(List.of(datasetPackage.getId())))
                    .thenReturn(List.of(datasetPackage));
            when(sampleRepo.countByDatasetVersionIdAndDeletedFalse(draft.getId()))
                    .thenReturn(1L);
            when(sampleRepo.findDuplicateExternalIdsByDatasetVersionId(draft.getId()))
                    .thenReturn(List.of());
            when(sampleRepo.findDuplicateSampleIndexesByDatasetVersionId(draft.getId()))
                    .thenReturn(List.of());
            when(sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                    draft.getId()
            )).thenReturn(0L);
            when(sampleRepo.findDistinctCreatedByPackageIdsByDatasetVersionId(
                    draft.getId()
            )).thenReturn(List.of(datasetPackage.getId()));
            when(dataRepo.countByDatasetVersionIdAndPackageIdIsNull(draft.getId()))
                    .thenReturn(0L);
            when(dataRepo.findDistinctPackageIdsByDatasetVersionId(draft.getId()))
                    .thenReturn(List.of(datasetPackage.getId()));
            when(annotationRepo.countByDatasetVersionIdAndPackageIdIsNull(
                    draft.getId()
            )).thenReturn(0L);
            when(annotationRepo.findDistinctPackageIdsByDatasetVersionId(
                    draft.getId()
            )).thenReturn(List.of(datasetPackage.getId()));
            when(versionRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(assetRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private void stubLockedVersionAndAsset() {
            when(versionRepo.findByIdAndDeletedFalse(draft.getId()))
                    .thenReturn(Optional.of(draft));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(draft.getId()))
                    .thenReturn(Optional.of(draft));
        }

        private void verifyNotPublished() {
            assertEquals("DRAFT", draft.getStatus());
            assertNull(draft.getPublishedAt());
            assertEquals(parent.getId(), asset.getCurrentVersionId());
            verify(versionRepo, never()).saveAndFlush(any());
            verify(assetRepo, never()).saveAndFlush(any());
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setName("dataset");
            asset.setOwnerUserId(7);
            asset.setCurrentVersionId("ready-2");
            asset.setDeleted(false);
            return asset;
        }

        private static DatasetVersion parent() {
            DatasetVersion version = new DatasetVersion();
            version.setId("ready-2");
            version.setAssetId("asset-1");
            version.setVersionNo(2);
            version.setStatus("READY");
            version.setPublishedAt(Instant.parse("2026-06-01T00:00:00Z"));
            version.setDeleted(false);
            return version;
        }

        private static DatasetVersion draft() {
            DatasetVersion version = new DatasetVersion();
            version.setId("draft-3");
            version.setAssetId("asset-1");
            version.setParentVersionId("ready-2");
            version.setWorkspaceHeadVersionId("ready-2");
            version.setVersion("1.0.3");
            version.setVersionLabel("1.0.3");
            version.setVersionNo(3);
            version.setStatus("DRAFT");
            version.setDeleted(false);
            return version;
        }

        private static DatasetPackage datasetPackage() {
            DatasetPackage datasetPackage = new DatasetPackage();
            datasetPackage.setId("package-primary");
            datasetPackage.setDatasetAssetId("asset-1");
            datasetPackage.setStoragePath("users/7/datasets/asset-1/v2/data.zip");
            datasetPackage.setFileName("data.zip");
            datasetPackage.setSizeBytes(100L);
            datasetPackage.setStatus("READY");
            datasetPackage.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            datasetPackage.setDeleted(false);
            return datasetPackage;
        }

        private static DatasetVersionPackage relation() {
            DatasetVersionPackage relation = new DatasetVersionPackage();
            relation.setDatasetVersionId("draft-3");
            relation.setPackageId("package-primary");
            relation.setPackageRole("PRIMARY");
            relation.setPackageOrder(0);
            relation.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            return relation;
        }
    }
}
