package com.tss.platform.service;

import com.tss.platform.dto.DatasetPackageCleanupPlanDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetPackageCleanupPlannerServiceTest {

    @Test
    void dryRunBlocksSharedPrimaryReferencedByParentReadyAndDraft() {
        Fixture fixture = new Fixture();
        DatasetVersion ready = fixture.readyVersion("ready-1");
        DatasetVersion draft = fixture.draftVersion("draft-2", ready.getId());
        fixture.stubPackageRelations(
                List.of(
                        fixture.relation(ready.getId(), fixture.datasetPackage.getId(), "PRIMARY", 0),
                        fixture.relation(draft.getId(), fixture.datasetPackage.getId(), "PRIMARY", 0)
                ),
                List.of(ready, draft)
        );
        when(fixture.assetRepo.countByCurrentVersionIdAndDeletedFalse(ready.getId()))
                .thenReturn(1L);
        when(fixture.versionRepo.findByParentVersionIdAndDeletedFalse(ready.getId()))
                .thenReturn(List.of(draft));

        DatasetPackageCleanupPlanDto result =
                fixture.service.dryRun(fixture.datasetPackage.getId());

        assertFalse(result.isCanDelete());
        assertReason(result, "ACTIVE_VERSION_REFERENCE");
        assertReason(result, "CURRENT_READY_VERSION");
        assertReason(result, "DRAFT_SHARES_PARENT_PRIMARY_PACKAGE");
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void dryRunBlocksAppendPackageReferencedByActiveDraft() {
        Fixture fixture = new Fixture();
        DatasetVersion draft = fixture.draftVersion("draft-2", "ready-1");
        fixture.stubPackageRelations(
                List.of(fixture.relation(draft.getId(), fixture.datasetPackage.getId(), "APPEND", 1)),
                List.of(draft)
        );

        DatasetPackageCleanupPlanDto result =
                fixture.service.dryRun(fixture.datasetPackage.getId());

        assertFalse(result.isCanDelete());
        assertReason(result, "ACTIVE_VERSION_REFERENCE");
    }

    @Test
    void dryRunAllowsPackageReferencedOnlyBySoftDeletedVersion() {
        Fixture fixture = new Fixture();
        DatasetVersion deleted = fixture.readyVersion("deleted-1");
        deleted.setDeleted(true);
        deleted.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
        fixture.stubPackageRelations(
                List.of(fixture.relation(deleted.getId(), fixture.datasetPackage.getId(), "APPEND", 1)),
                List.of(deleted)
        );

        DatasetPackageCleanupPlanDto result =
                fixture.service.dryRun(fixture.datasetPackage.getId());

        assertTrue(result.isCanDelete());
        assertTrue(result.getBlockers().isEmpty());
        assertEquals(List.of(deleted.getId()), result.getReferencedVersionIds());
    }

    @Test
    void dryRunBlocksCurrentReadyVersionPackage() {
        Fixture fixture = new Fixture();
        DatasetVersion ready = fixture.readyVersion("ready-1");
        fixture.stubPackageRelations(
                List.of(fixture.relation(ready.getId(), fixture.datasetPackage.getId(), "PRIMARY", 0)),
                List.of(ready)
        );
        when(fixture.assetRepo.countByCurrentVersionIdAndDeletedFalse(ready.getId()))
                .thenReturn(1L);

        DatasetPackageCleanupPlanDto result =
                fixture.service.dryRun(fixture.datasetPackage.getId());

        assertFalse(result.isCanDelete());
        assertReason(result, "CURRENT_READY_VERSION");
    }

    @Test
    void dryRunBlocksTrainingReferencedVersionPackageEvenAfterSoftDelete() {
        Fixture fixture = new Fixture();
        DatasetVersion deleted = fixture.readyVersion("deleted-1");
        deleted.setDeleted(true);
        deleted.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
        fixture.stubPackageRelations(
                List.of(fixture.relation(deleted.getId(), fixture.datasetPackage.getId(), "PRIMARY", 0)),
                List.of(deleted)
        );
        when(fixture.trainingRepo.countByDatasetVersionIdIn(List.of(deleted.getId())))
                .thenReturn(1L);

        DatasetPackageCleanupPlanDto result =
                fixture.service.dryRun(fixture.datasetPackage.getId());

        assertFalse(result.isCanDelete());
        assertReason(result, "TRAINING_VERSION_REFERENCE");
    }

    @Test
    void enqueueOnlyCreatesMinioDeleteTaskWhenDryRunIsSafe() {
        Fixture fixture = new Fixture();
        DatasetVersion deleted = fixture.readyVersion("deleted-1");
        deleted.setDeleted(true);
        deleted.setDeletedAt(Instant.parse("2026-06-01T00:00:00Z"));
        fixture.stubPackageRelations(
                List.of(fixture.relation(deleted.getId(), fixture.datasetPackage.getId(), "APPEND", 1)),
                List.of(deleted)
        );
        MinioDeleteTask task = new MinioDeleteTask();
        task.setId("minio-del-1");
        when(fixture.deleteTaskService.enqueueDefaultBucketDelete(
                eq(fixture.datasetPackage.getStoragePath()),
                eq(MinioDeleteTaskService.SOURCE_DATASET_PACKAGE),
                eq(fixture.datasetPackage.getId()),
                eq(fixture.asset.getOwnerUserId())
        )).thenReturn(task);

        DatasetPackageCleanupPlanDto result =
                fixture.service.enqueueIfSafe(fixture.datasetPackage.getId());

        assertTrue(result.isCanDelete());
        assertTrue(result.isEnqueued());
        assertEquals(task.getId(), result.getMinioDeleteTaskId());
        verify(fixture.deleteTaskService).enqueueDefaultBucketDelete(
                fixture.datasetPackage.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                fixture.datasetPackage.getId(),
                fixture.asset.getOwnerUserId()
        );
    }

    @Test
    void enqueueDoesNotCreateMinioDeleteTaskWhenBlocked() {
        Fixture fixture = new Fixture();
        DatasetVersion draft = fixture.draftVersion("draft-2", "ready-1");
        fixture.stubPackageRelations(
                List.of(fixture.relation(draft.getId(), fixture.datasetPackage.getId(), "APPEND", 1)),
                List.of(draft)
        );

        DatasetPackageCleanupPlanDto result =
                fixture.service.enqueueIfSafe(fixture.datasetPackage.getId());

        assertFalse(result.isCanDelete());
        assertFalse(result.isEnqueued());
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                any(),
                any(),
                any(),
                any()
        );
    }

    private static void assertReason(
            DatasetPackageCleanupPlanDto result,
            String code
    ) {
        assertTrue(
                result.getBlockers().stream().anyMatch(reason ->
                        code.equals(reason.getCode())),
                "Expected cleanup blocker: " + code
        );
    }

    private static final class Fixture {
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final ImportJobRepository importJobRepo =
                mock(ImportJobRepository.class);
        private final TrainingExperimentVersionRepository trainingRepo =
                mock(TrainingExperimentVersionRepository.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final DatasetAsset asset = asset();
        private final DatasetPackage datasetPackage = datasetPackage();
        private final DatasetPackageCleanupPlannerService service =
                new DatasetPackageCleanupPlannerService(
                        packageRepo,
                        versionPackageRepo,
                        versionRepo,
                        assetRepo,
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        importJobRepo,
                        trainingRepo,
                        deleteTaskService
                );

        private Fixture() {
            when(packageRepo.findByIdAndDeletedFalse(datasetPackage.getId()))
                    .thenReturn(Optional.of(datasetPackage));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(sampleRepo.countActiveByCreatedByPackageId(datasetPackage.getId()))
                    .thenReturn(0L);
            when(dataRepo.countActiveByPackageId(datasetPackage.getId()))
                    .thenReturn(0L);
            when(annotationRepo.countActiveByPackageId(datasetPackage.getId()))
                    .thenReturn(0L);
            when(importJobRepo.countActiveByPackageId(datasetPackage.getId()))
                    .thenReturn(0L);
        }

        private void stubPackageRelations(
                List<DatasetVersionPackage> relations,
                List<DatasetVersion> versions
        ) {
            when(versionPackageRepo.findByPackageId(datasetPackage.getId()))
                    .thenReturn(relations);
            when(versionRepo.findAllById(any())).thenReturn(versions);
            when(trainingRepo.countByDatasetVersionIdIn(any())).thenReturn(0L);
            for (DatasetVersion version : versions) {
                when(assetRepo.countByCurrentVersionIdAndDeletedFalse(version.getId()))
                        .thenReturn(0L);
                when(versionRepo.findByParentVersionIdAndDeletedFalse(version.getId()))
                        .thenReturn(List.of());
            }
        }

        private DatasetVersionPackage relation(
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
            relation.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            return relation;
        }

        private DatasetVersion readyVersion(String id) {
            DatasetVersion version = new DatasetVersion();
            version.setId(id);
            version.setAssetId(asset.getId());
            version.setStatus("READY");
            version.setDeleted(false);
            version.setOwnerUserId(asset.getOwnerUserId());
            return version;
        }

        private DatasetVersion draftVersion(String id, String parentVersionId) {
            DatasetVersion version = new DatasetVersion();
            version.setId(id);
            version.setAssetId(asset.getId());
            version.setStatus("DRAFT");
            version.setParentVersionId(parentVersionId);
            version.setDeleted(false);
            version.setOwnerUserId(asset.getOwnerUserId());
            return version;
        }

        private static DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setName("dataset");
            value.setOwnerUserId(7);
            value.setCurrentVersionId("ready-1");
            value.setDeleted(false);
            return value;
        }

        private static DatasetPackage datasetPackage() {
            DatasetPackage value = new DatasetPackage();
            value.setId("package-1");
            value.setDatasetAssetId("asset-1");
            value.setStoragePath("users/7/datasets/asset-1/packages/package-1.zip");
            value.setFileName("package-1.zip");
            value.setSizeBytes(100L);
            value.setStatus("READY");
            value.setDeleted(false);
            value.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            return value;
        }
    }
}
