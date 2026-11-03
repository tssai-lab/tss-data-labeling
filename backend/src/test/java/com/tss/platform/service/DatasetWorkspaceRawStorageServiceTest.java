package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceRawStorageServiceTest {

    @Test
    void attachesImmutableRawObjectAsNextOverlayPackage() {
        Fixture fixture = new Fixture();
        when(fixture.versionPackageRepo
                .findMaxPackageOrderByDatasetVersionId("workspace-1"))
                .thenReturn(2);

        DatasetPackage result = fixture.service.attachRawObject(
                fixture.asset,
                fixture.workspace,
                "users/7/datasets/asset-1/workspaces/workspace-1/file.json",
                "file.json",
                12L,
                "a".repeat(64)
        );

        assertEquals("RAW", result.getStorageKind());
        assertEquals("READY", result.getStatus());
        assertFalse(result.getDeleted());
        ArgumentCaptor<DatasetVersionPackage> relation =
                ArgumentCaptor.forClass(DatasetVersionPackage.class);
        verify(fixture.versionPackageRepo).save(relation.capture());
        assertEquals("workspace-1", relation.getValue().getDatasetVersionId());
        assertEquals(result.getId(), relation.getValue().getPackageId());
        assertEquals("OVERLAY", relation.getValue().getPackageRole());
        assertEquals(3, relation.getValue().getPackageOrder());
    }

    @Test
    void releasesUnreferencedWorkspaceOverlayAndQueuesObjectDeletion() {
        Fixture fixture = new Fixture();
        DatasetPackage datasetPackage = fixture.rawPackage();
        DatasetVersionPackage relation = fixture.overlayRelation(datasetPackage);
        when(fixture.versionPackageRepo.findByDatasetVersionIdAndPackageId(
                "workspace-1",
                datasetPackage.getId()
        )).thenReturn(Optional.of(relation));
        when(fixture.dataRepo.countByDatasetVersionIdAndPackageId(
                "workspace-1",
                datasetPackage.getId()
        )).thenReturn(0L);
        when(fixture.annotationRepo.countByDatasetVersionIdAndPackageId(
                "workspace-1",
                datasetPackage.getId()
        )).thenReturn(0L);
        when(fixture.versionPackageRepo.findByPackageId(datasetPackage.getId()))
                .thenReturn(List.of(relation));
        when(fixture.packageRepo.findById(datasetPackage.getId()))
                .thenReturn(Optional.of(datasetPackage));

        fixture.service.releaseIfUnreferenced(
                fixture.workspace,
                datasetPackage.getId(),
                7
        );

        verify(fixture.versionPackageRepo).delete(relation);
        verify(fixture.versionPackageRepo).flush();
        verify(fixture.versionPackageRepo).compactPackageOrder(
                "workspace-1",
                2
        );
        assertTrue(datasetPackage.getDeleted());
        assertEquals("SUPERSEDED", datasetPackage.getStatus());
        verify(fixture.deleteTaskService).enqueueDefaultBucketDelete(
                datasetPackage.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                datasetPackage.getId(),
                7
        );
    }

    @Test
    void keepsOverlayWhileDeletedResourceStillReferencesItForRestore() {
        Fixture fixture = new Fixture();
        DatasetPackage datasetPackage = fixture.rawPackage();
        DatasetVersionPackage relation = fixture.overlayRelation(datasetPackage);
        when(fixture.versionPackageRepo.findByDatasetVersionIdAndPackageId(
                "workspace-1",
                datasetPackage.getId()
        )).thenReturn(Optional.of(relation));
        when(fixture.dataRepo.countByDatasetVersionIdAndPackageId(
                "workspace-1",
                datasetPackage.getId()
        )).thenReturn(1L);

        fixture.service.releaseIfUnreferenced(
                fixture.workspace,
                datasetPackage.getId(),
                7
        );

        verify(fixture.versionPackageRepo, never()).delete(any());
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(),
                anyString(),
                anyString(),
                any()
        );
    }

    @Test
    void queuesUploadedRawObjectForCleanupWhenTransactionRollsBack()
            throws Exception {
        Fixture fixture = new Fixture();
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        DatasetWorkspaceTextFilePolicy.ValidatedText text =
                new DatasetWorkspaceTextFilePolicy.ValidatedText(
                        bytes,
                        "labels.json",
                        "json",
                        "application/json",
                        "b".repeat(64)
                );
        List<TransactionSynchronization> synchronizations;
        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.service.storeText(fixture.asset, fixture.workspace, text);
            synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertEquals(1, synchronizations.size());
        synchronizations.forEach(value -> value.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK
        ));
        verify(fixture.deleteTaskService)
                .enqueueDefaultBucketDeleteImmediately(
                        anyString(),
                        eq(MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK),
                        anyString(),
                        eq(7)
                );
        verify(fixture.minioService).uploadStream(
                anyString(),
                any(),
                eq((long) bytes.length),
                eq("application/json")
        );
    }

    private static final class Fixture {
        private final MinioService minioService = mock(MinioService.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetAsset asset = asset();
        private final DatasetVersion workspace = workspace();
        private final DatasetWorkspaceRawStorageService service =
                new DatasetWorkspaceRawStorageService(
                        minioService,
                        deleteTaskService,
                        packageRepo,
                        versionPackageRepo,
                        dataRepo,
                        annotationRepo
                );

        private Fixture() {
            when(packageRepo.save(any(DatasetPackage.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(versionPackageRepo.save(any(DatasetVersionPackage.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(versionPackageRepo
                    .findMaxPackageOrderByDatasetVersionId("workspace-1"))
                    .thenReturn(null);
        }

        private DatasetPackage rawPackage() {
            DatasetPackage datasetPackage = new DatasetPackage();
            datasetPackage.setId("package-1");
            datasetPackage.setDatasetAssetId("asset-1");
            datasetPackage.setStoragePath(
                    "users/7/datasets/asset-1/workspaces/workspace-1/file.json"
            );
            datasetPackage.setStorageKind("RAW");
            datasetPackage.setStatus("READY");
            datasetPackage.setDeleted(false);
            return datasetPackage;
        }

        private DatasetVersionPackage overlayRelation(
                DatasetPackage datasetPackage
        ) {
            DatasetVersionPackage relation = new DatasetVersionPackage();
            relation.setDatasetVersionId("workspace-1");
            relation.setPackageId(datasetPackage.getId());
            relation.setPackageRole("OVERLAY");
            relation.setPackageOrder(2);
            return relation;
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            return asset;
        }

        private static DatasetVersion workspace() {
            DatasetVersion workspace = new DatasetVersion();
            workspace.setId("workspace-1");
            workspace.setAssetId("asset-1");
            workspace.setStatus("DRAFT");
            return workspace;
        }
    }
}
