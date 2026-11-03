package com.tss.platform.service;

import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetCatalogQueryServiceTest {

    @Test
    void detailReadsOnlyRequestedAssetWithDraftAndImportMetadata() {
        var assets = mock(DatasetAssetRepository.class);
        var versions = mock(DatasetVersionRepository.class);
        var jobs = mock(ImportJobRepository.class);
        var auth = mock(AuthContext.class);
        var asset = new com.tss.platform.entity.DatasetAsset();
        asset.setId("asset-outside-first-200");
        asset.setOwnerUserId(7);
        var draft = new com.tss.platform.entity.DatasetVersion();
        draft.setId("draft-201");
        draft.setAssetId(asset.getId());
        draft.setStatus("DRAFT");
        var job = new com.tss.platform.entity.ImportJob();
        job.setId("import-201");
        job.setDatasetVersionId(draft.getId());
        job.setStatus("RUNNING");
        when(assets.findByIdAndDeletedFalse(asset.getId())).thenReturn(java.util.Optional.of(asset));
        when(auth.canAccessOwner(7)).thenReturn(true);
        when(versions.findByAssetIdInAndDeletedFalse(java.util.Set.of(asset.getId()))).thenReturn(List.of(draft));
        when(jobs.findByDatasetVersionIdIn(java.util.Set.of(draft.getId()))).thenReturn(List.of(job));
        var service = new DatasetCatalogQueryService(assets, versions, jobs, mock(DatasetVersionFileCountService.class), auth);

        var detail = service.getByAssetId(asset.getId());

        org.junit.jupiter.api.Assertions.assertSame(draft, detail.latestDraft());
        org.junit.jupiter.api.Assertions.assertSame(job, detail.latestDraftImportJob());
        verify(assets).findByIdAndDeletedFalse(asset.getId());
        org.mockito.Mockito.verifyNoMoreInteractions(assets);
    }

    @Test
    void detailRejectsMissingDeletedAndForeignAssetsBeforeReadingTheirVersions() {
        var assets = mock(DatasetAssetRepository.class);
        var versions = mock(DatasetVersionRepository.class);
        var jobs = mock(ImportJobRepository.class);
        var auth = mock(AuthContext.class);
        var service = new DatasetCatalogQueryService(assets, versions, jobs, mock(DatasetVersionFileCountService.class), auth);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.getByAssetId("missing-or-deleted"));
        var foreign = new com.tss.platform.entity.DatasetAsset();
        foreign.setOwnerUserId(8);
        when(assets.findByIdAndDeletedFalse("foreign")).thenReturn(java.util.Optional.of(foreign));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.getByAssetId("foreign"));
        org.mockito.Mockito.verifyNoInteractions(versions, jobs);
    }

    @Test
    void normalizesAndEscapesNameKeywordForOwnerCatalog() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetCatalogQueryService service = service(assetRepo, authContext);
        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                eq("CV"),
                eq("100!%!_\\!!"),
                any(Pageable.class)
        )).thenReturn(Page.empty());

        service.list("cv", " 100%_\\! ", 1, null, 20);

        verify(assetRepo).searchCatalogForOwner(
                eq(7),
                eq("CV"),
                eq("100!%!_\\!!"),
                any(Pageable.class)
        );
    }

    @Test
    void treatsBlankNameKeywordAsNoFilterForAdminCatalog() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetCatalogQueryService service = service(assetRepo, authContext);
        when(authContext.isAdmin()).thenReturn(true);
        when(assetRepo.searchCatalogForAdmin(
                isNull(),
                isNull(),
                any(Pageable.class)
        )).thenReturn(Page.empty());

        service.list(null, "   ", 1, null, 20);

        verify(assetRepo).searchCatalogForAdmin(
                isNull(),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void trainingCandidateFilterRunsInDatabaseBeforePaging() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetCatalogQueryService service = service(assetRepo, authContext);
        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchTrainingCandidates(
                eq(7),
                isNull(),
                isNull(),
                eq(List.of("YOLO", "FOLDER_CLASSIFICATION")),
                any(Pageable.class)
        )).thenReturn(Page.empty());

        service.listTrainingCandidates(
                null,
                null,
                3,
                null,
                20,
                List.of(" YOLO ", "FOLDER_CLASSIFICATION", "YOLO")
        );

        verify(assetRepo).searchTrainingCandidates(
                eq(7),
                isNull(),
                isNull(),
                eq(List.of("YOLO", "FOLDER_CLASSIFICATION")),
                any(Pageable.class)
        );
    }

    private static DatasetCatalogQueryService service(
            DatasetAssetRepository assetRepo,
            AuthContext authContext
    ) {
        return new DatasetCatalogQueryService(
                assetRepo,
                mock(DatasetVersionRepository.class),
                mock(ImportJobRepository.class),
                mock(DatasetVersionFileCountService.class),
                authContext
        );
    }
}
