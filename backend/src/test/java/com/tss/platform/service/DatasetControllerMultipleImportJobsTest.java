package com.tss.platform.service;

import com.tss.platform.controller.DatasetController;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetControllerMultipleImportJobsTest {

    @Test
    @SuppressWarnings("unchecked")
    void listSelectsNewestImportJobWhenDraftHasMultiplePackages() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller =
                new DatasetController(
                        assetRepo,
                        versionRepo,
                        importJobRepo,
                        authContext,
                        fileCountService
                );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("multimodal");
        asset.setType("MULTIMODAL");
        asset.setOwnerUserId(7);
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        DatasetVersion draft = new DatasetVersion();
        draft.setId("draft-1");
        draft.setAssetId(asset.getId());
        draft.setVersionNo(1);
        draft.setVersion("v1");
        draft.setVersionLabel("v1");
        draft.setStatus("DRAFT");
        draft.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        ImportJob older = importJob(
                "ijob-primary",
                draft.getId(),
                "package-primary",
                "SUCCESS",
                100,
                "2026-01-01T01:00:00Z"
        );
        ImportJob newer = importJob(
                "ijob-append",
                draft.getId(),
                "package-append",
                "RUNNING",
                40,
                "2026-01-02T01:00:00Z"
        );

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(asset), Pageable.unpaged(), 1));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(draft));
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of(older, newer));

        ApiResponse<Map<String, Object>> response =
                controller.list(null, null, null, null, null);

        List<Map<String, Object>> data =
                (List<Map<String, Object>>) response.getData().get("data");
        Map<String, Object> item = data.get(0);
        assertEquals("ijob-append", item.get("importJobId"));
        assertEquals("RUNNING", item.get("importStatus"));
        assertEquals(40, item.get("importProgress"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsesPublishedWorkspaceAsCurrentAndNoLongerReportsItAsDraft() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller =
                new DatasetController(
                        assetRepo,
                        versionRepo,
                        importJobRepo,
                        authContext,
                        fileCountService
                );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("multimodal");
        asset.setType("MULTIMODAL");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("ready-3");
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        DatasetVersion parent = readyVersion(
                "ready-2",
                asset.getId(),
                2,
                "2026-01-01T00:00:00Z"
        );
        DatasetVersion published = readyVersion(
                "ready-3",
                asset.getId(),
                3,
                "2026-01-02T00:00:00Z"
        );
        published.setParentVersionId(parent.getId());

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(asset), Pageable.unpaged(), 1));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection()))
                .thenReturn(List.of(parent, published));

        ApiResponse<Map<String, Object>> response =
                controller.list(null, null, null, null, null);

        List<Map<String, Object>> data =
                (List<Map<String, Object>>) response.getData().get("data");
        Map<String, Object> item = data.get(0);
        assertEquals(published.getId(), item.get("currentVersionId"));
        assertEquals("READY", item.get("versionStatus"));
        assertNull(item.get("latestDraftVersionId"));
        assertNull(item.get("importJobId"));
    }

    private DatasetVersion readyVersion(
            String id,
            String assetId,
            int versionNo,
            String createdAt
    ) {
        DatasetVersion version = new DatasetVersion();
        version.setId(id);
        version.setAssetId(assetId);
        version.setVersionNo(versionNo);
        version.setVersion("v" + versionNo);
        version.setVersionLabel("v" + versionNo);
        version.setStatus("READY");
        version.setCreatedAt(Instant.parse(createdAt));
        return version;
    }

    private ImportJob importJob(
            String id,
            String versionId,
            String packageId,
            String status,
            int progress,
            String createdAt
    ) {
        ImportJob job = new ImportJob();
        job.setId(id);
        job.setDatasetVersionId(versionId);
        job.setPackageId(packageId);
        job.setStatus(status);
        job.setProgress(progress);
        job.setCreatedAt(Instant.parse(createdAt));
        return job;
    }
}
