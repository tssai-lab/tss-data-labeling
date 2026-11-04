package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetCatalogReadinessRepository;
import com.tss.platform.repository.DatasetCatalogReadinessSnapshot;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceReadinessServiceTest {

    @Test
    void validRawWorkspaceIsPublishableAtTheEvaluatedRevision() {
        Fixture fixture = new Fixture();

        var readiness = fixture.service.evaluate(fixture.asset, fixture.workspace);

        assertTrue(readiness.canPublish());
        assertEquals(9L, readiness.evaluatedRevision());
        assertTrue(readiness.blockers().isEmpty());
    }

    @Test
    void exposesBusyAndStaleConditionsAsStableBlockers() {
        Fixture fixture = new Fixture();
        fixture.asset.setCurrentVersionId("ready-newer");
        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setId("upload-1");
        upload.setStatus("UPLOADING");
        when(fixture.uploadRepo.findByVersionId("workspace-2"))
                .thenReturn(List.of(upload));

        var readiness = fixture.service.evaluate(fixture.asset, fixture.workspace);

        assertFalse(readiness.canPublish());
        assertTrue(readiness.blockers().stream()
                .anyMatch(blocker -> "BASE_VERSION_STALE".equals(blocker.code())));
        assertTrue(readiness.blockers().stream()
                .anyMatch(blocker -> "ACTIVE_UPLOAD".equals(blocker.code())
                        && "upload-1".equals(blocker.resourceId())));
    }

    @Test
    void historicalReadyBaseIsPublishableWhileCapturedHeadIsUnchanged() {
        Fixture fixture = new Fixture();
        DatasetVersion current = new DatasetVersion();
        current.setId("ready-2");
        current.setAssetId("asset-1");
        current.setVersionNo(2);
        current.setStatus("READY");
        fixture.asset.setCurrentVersionId("ready-2");
        fixture.workspace.setWorkspaceHeadVersionId("ready-2");
        fixture.workspace.setVersionNo(3);
        when(fixture.versionRepo.findByIdAndDeletedFalse("ready-2"))
                .thenReturn(Optional.of(current));

        var readiness = fixture.service.evaluate(
                fixture.asset,
                fixture.workspace
        );

        assertTrue(readiness.canPublish());
        assertTrue(readiness.blockers().isEmpty());
    }

    @Test
    void exposesFailedUploadAsAStableBlocker() {
        Fixture fixture = new Fixture();
        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setId("upload-failed");
        upload.setStatus("FAILED");
        when(fixture.uploadRepo.findByVersionId("workspace-2"))
                .thenReturn(List.of(upload));

        var readiness = fixture.service.evaluate(fixture.asset, fixture.workspace);

        assertFalse(readiness.canPublish());
        assertTrue(readiness.blockers().stream()
                .anyMatch(blocker ->
                        "UPLOAD_NOT_SUCCESSFUL".equals(blocker.code())
                                && "upload-failed".equals(
                                blocker.resourceId()
                        )));
    }

    @Test
    void catalogEvaluationUsesAggregateFlagsWithoutLoadingWorkspaceContent() {
        Fixture fixture = new Fixture();

        var readiness = fixture.service.evaluateCatalog(
                fixture.asset,
                fixture.workspace
        );

        assertTrue(readiness.canPublish());
        assertEquals(9L, readiness.evaluatedRevision());
        verify(fixture.catalogReadinessRepo).inspect(
                "workspace-2",
                "asset-1"
        );
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        "workspace-2"
                );
        verify(fixture.dataRepo, never()).findByDatasetVersionId("workspace-2");
        verify(fixture.annotationRepo, never())
                .findByDatasetVersionId("workspace-2");
        verify(fixture.uploadRepo, never()).findByVersionId("workspace-2");
        verify(fixture.importRepo, never())
                .findByDatasetVersionId("workspace-2");
    }

    @Test
    void catalogEvaluationMapsAggregateFailureToStableBlocker() {
        Fixture fixture = new Fixture();
        when(fixture.catalogReadinessRepo.inspect(
                "workspace-2",
                "asset-1"
        )).thenReturn(catalogFlags(true));

        var readiness = fixture.service.evaluateCatalog(
                fixture.asset,
                fixture.workspace
        );

        assertFalse(readiness.canPublish());
        assertTrue(readiness.blockers().stream().anyMatch(blocker ->
                "RESOURCE_STORAGE_INVALID".equals(blocker.code())
        ));
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetCatalogReadinessRepository catalogReadinessRepo =
                mock(DatasetCatalogReadinessRepository.class);
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
        private final DatasetUploadSessionRepository uploadRepo =
                mock(DatasetUploadSessionRepository.class);
        private final ImportJobRepository importRepo =
                mock(ImportJobRepository.class);
        private final DatasetWorkspaceReadinessService service =
                new DatasetWorkspaceReadinessService(
                        versionRepo,
                        catalogReadinessRepo,
                        versionPackageRepo,
                        packageRepo,
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        uploadRepo,
                        importRepo
                );
        private final DatasetAsset asset = asset();
        private final DatasetVersion workspace = workspace();

        private Fixture() {
            DatasetVersion parent = new DatasetVersion();
            parent.setId("ready-1");
            parent.setAssetId("asset-1");
            parent.setVersionNo(1);
            parent.setStatus("READY");

            DatasetVersionPackage relation = new DatasetVersionPackage();
            relation.setDatasetVersionId("workspace-2");
            relation.setPackageId("package-raw");
            relation.setPackageRole("PRIMARY");
            relation.setPackageOrder(0);

            DatasetPackage datasetPackage = new DatasetPackage();
            datasetPackage.setId("package-raw");
            datasetPackage.setDatasetAssetId("asset-1");
            datasetPackage.setStatus("READY");
            datasetPackage.setStorageKind("RAW");
            datasetPackage.setStoragePath(
                    "users/7/datasets/asset-1/workspaces/workspace-2/raw.json"
            );
            datasetPackage.setDeleted(false);

            DatasetSample sample = new DatasetSample();
            sample.setId("sample-1");
            sample.setDatasetVersionId("workspace-2");
            sample.setExternalId("scene-1");
            sample.setSampleIndex(0);
            sample.setDeleted(false);

            DatasetSampleData data = new DatasetSampleData();
            data.setId("data-1");
            data.setDatasetVersionId("workspace-2");
            data.setSampleId("sample-1");
            data.setPackageId("package-raw");
            data.setDataType("TEXT");
            data.setSeq(0);
            data.setFormat("json");
            data.setFileName("raw.json");
            data.setContentType("application/json");
            data.setSizeBytes(2L);
            data.setChecksum("a".repeat(64));
            data.setDeleted(false);

            when(versionRepo.findByIdAndDeletedFalse("ready-1"))
                    .thenReturn(Optional.of(parent));
            when(catalogReadinessRepo.inspect(
                    "workspace-2",
                    "asset-1"
            )).thenReturn(catalogFlags(false));
            when(versionPackageRepo
                    .findByDatasetVersionIdOrderByPackageOrderAsc("workspace-2"))
                    .thenReturn(List.of(relation));
            when(packageRepo.findByIdAndDeletedFalse("package-raw"))
                    .thenReturn(Optional.of(datasetPackage));
            when(sampleRepo
                    .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                            "workspace-2"
                    )).thenReturn(List.of(sample));
            when(sampleRepo.findDuplicateExternalIdsByDatasetVersionId(
                    "workspace-2"
            )).thenReturn(List.of());
            when(sampleRepo.findDuplicateSampleIndexesByDatasetVersionId(
                    "workspace-2"
            )).thenReturn(List.of());
            when(dataRepo.findByDatasetVersionId("workspace-2"))
                    .thenReturn(List.of(data));
            when(annotationRepo.findByDatasetVersionId("workspace-2"))
                    .thenReturn(List.of());
            when(uploadRepo.findByVersionId("workspace-2"))
                    .thenReturn(List.of());
            when(importRepo.findByDatasetVersionId("workspace-2"))
                    .thenReturn(List.of());
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setType("MULTIMODAL");
            asset.setCurrentVersionId("ready-1");
            return asset;
        }

        private static DatasetVersion workspace() {
            DatasetVersion workspace = new DatasetVersion();
            workspace.setId("workspace-2");
            workspace.setAssetId("asset-1");
            workspace.setParentVersionId("ready-1");
            workspace.setWorkspaceHeadVersionId("ready-1");
            workspace.setVersionNo(2);
            workspace.setStatus("DRAFT");
            workspace.setWorkspaceRevision(9L);
            return workspace;
        }
    }

    private static DatasetCatalogReadinessSnapshot catalogFlags(
            boolean resourceStorageInvalid
    ) {
        return new DatasetCatalogReadinessSnapshot(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                resourceStorageInvalid,
                false
        );
    }
}
