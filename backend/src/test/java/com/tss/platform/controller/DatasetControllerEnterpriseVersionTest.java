package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.DatasetVersionFileCountService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;

class DatasetControllerEnterpriseVersionTest {

    @Test
    @SuppressWarnings("unchecked")
    void listUsesCurrentVersionInsteadOfNewestCreatedVersion() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller = new DatasetController(
                assetRepo,
                versionRepo,
                importJobRepo,
                authContext,
                fileCountService
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("pcb");
        asset.setType("CV");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        DatasetVersion current = version("dataset-ver-1", 1, "v1", "READY", "baseline",
                Instant.parse("2026-01-01T00:00:00Z"));
        DatasetVersion newer = version("dataset-ver-2", 2, "v2", "READY", "bad release",
                Instant.parse("2026-02-01T00:00:00Z"));

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(asset), Pageable.unpaged(), 1));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(current, newer));
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of());
        when(fileCountService.countCurrentVersionFiles(asset, current)).thenReturn(12L);

        ApiResponse<Map<String, Object>> response = controller.list(null, null, null, null, null);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getData().get("data");
        Map<String, Object> item = data.get(0);
        assertEquals("dataset-ver-1", item.get("versionId"));
        assertEquals("dataset-ver-1", item.get("currentVersionId"));
        assertEquals(1, item.get("currentVersionNo"));
        assertEquals("v1", item.get("currentVersionLabel"));
        assertEquals("READY", item.get("versionStatus"));
        assertEquals("baseline", item.get("versionDescription"));
        assertFalse(item.containsKey("storagePath"));
        assertEquals(12L, item.get("fileCount"));
        assertEquals(12L, item.get("currentVersionFileCount"));
        assertEquals(2, item.get("versionCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsLatestDraftAndImportStatusWithoutChangingCurrentVersionMeaning() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller = new DatasetController(
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
        asset.setCurrentVersionId(null);
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        DatasetVersion draft1 = version(
                "dataset-ver-1",
                1,
                "v1",
                "DRAFT",
                "first draft",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        DatasetVersion draft2 = version(
                "dataset-ver-2",
                2,
                "v2",
                "DRAFT",
                "latest draft",
                Instant.parse("2026-02-01T00:00:00Z")
        );
        ImportJob job = new ImportJob();
        job.setId("ijob-2");
        job.setDatasetVersionId(draft2.getId());
        job.setStatus("FAILED");
        job.setProgress(35);
        job.setErrorMessage("invalid manifest");

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(asset), Pageable.unpaged(), 1));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection()))
                .thenReturn(List.of(draft1, draft2));
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of(job));

        ApiResponse<Map<String, Object>> response = controller.list(null, null, null, null, null);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getData().get("data");
        Map<String, Object> item = data.get(0);
        assertEquals(null, item.get("currentVersionId"));
        assertEquals(null, item.get("versionStatus"));
        assertEquals("-", item.get("size"));
        assertEquals("dataset-ver-2", item.get("latestDraftVersionId"));
        assertEquals("ijob-2", item.get("importJobId"));
        assertEquals("FAILED", item.get("importStatus"));
        assertEquals(35, item.get("importProgress"));
        assertEquals("invalid manifest", item.get("importErrorMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listShowsWorkspaceDraftWhileKeepingReadyCurrentVersion() {
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
        asset.setCurrentVersionId("ready-2");
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        DatasetVersion ready = version(
                "ready-2",
                2,
                "v2",
                "READY",
                "stable snapshot",
                Instant.parse("2026-02-01T00:00:00Z")
        );
        DatasetVersion workspace = version(
                "draft-3",
                3,
                "v3",
                "DRAFT",
                "workspace draft",
                Instant.parse("2026-03-01T00:00:00Z")
        );
        workspace.setParentVersionId(ready.getId());

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(asset), Pageable.unpaged(), 1));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection()))
                .thenReturn(List.of(ready, workspace));
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response =
                controller.list(null, null, null, null, null);

        List<Map<String, Object>> data =
                (List<Map<String, Object>>) response.getData().get("data");
        Map<String, Object> item = data.get(0);
        assertEquals("ready-2", item.get("currentVersionId"));
        assertEquals("READY", item.get("versionStatus"));
        assertEquals("draft-3", item.get("latestDraftVersionId"));
        assertEquals(null, item.get("importJobId"));
        assertEquals(null, item.get("importStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listEnrichesOnlyAssetsReturnedByDatabasePage() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller = new DatasetController(
                assetRepo,
                versionRepo,
                importJobRepo,
                authContext,
                fileCountService
        );

        DatasetAsset first = asset("asset-1", "first");
        DatasetAsset second = asset("asset-2", "second");
        DatasetVersion firstReady = version("ready-1", "asset-1", 1, "v1", "READY", "first",
                Instant.parse("2026-01-01T00:00:00Z"));
        DatasetVersion secondReady = version("ready-2", "asset-2", 1, "v1", "READY", "second",
                Instant.parse("2026-01-02T00:00:00Z"));
        first.setCurrentVersionId(firstReady.getId());
        second.setCurrentVersionId(secondReady.getId());

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(second), Pageable.unpaged(), 2));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection())).thenReturn(List.of(secondReady));
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of());
        when(fileCountService.countCurrentVersionFiles(second, secondReady)).thenReturn(22L);

        ApiResponse<Map<String, Object>> response = controller.list(null, null, 2, null, 1);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getData().get("data");
        assertEquals(2L, response.getData().get("total"));
        assertEquals(2, response.getData().get("page"));
        assertEquals(1, response.getData().get("pageSize"));
        assertEquals(1, data.size());
        assertEquals("asset-2", data.get(0).get("assetId"));
        assertEquals(22L, data.get(0).get("fileCount"));
        verify(fileCountService).countCurrentVersionFiles(second, secondReady);
        verify(fileCountService, never()).countCurrentVersionFiles(first, firstReady);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWithoutPageSizeKeepsLegacyReturnAllDefault() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetVersionFileCountService fileCountService = mock(DatasetVersionFileCountService.class);
        DatasetController controller = new DatasetController(
                assetRepo,
                versionRepo,
                importJobRepo,
                authContext,
                fileCountService
        );

        DatasetAsset first = asset("asset-1", "first");
        DatasetAsset second = asset("asset-2", "second");
        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepo.searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(first, second), Pageable.unpaged(), 2));
        when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection())).thenReturn(List.of());
        when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response = controller.list(null, null, null, null, null);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getData().get("data");
        assertEquals(2, data.size());
        assertEquals(2, response.getData().get("pageSize"));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepo).searchCatalogForOwner(
                eq(7),
                nullable(String.class),
                nullable(String.class),
                pageableCaptor.capture()
        );
        assertTrue(pageableCaptor.getValue().isUnpaged());
    }

    @Test
    void listReturnsApiFailureForInvalidType() {
        DatasetController controller = new DatasetController(
                mock(DatasetAssetRepository.class),
                mock(DatasetVersionRepository.class),
                mock(ImportJobRepository.class),
                mock(AuthContext.class),
                mock(DatasetVersionFileCountService.class)
        );

        ApiResponse<Map<String, Object>> response =
                controller.list("INVALID", null, null, null, null);

        assertFalse(response.isSuccess());
        assertEquals(
                "任务类型仅支持 CV, NLP, POINT_CLOUD, ROBOT, LEROBOT, MULTIMODAL, OTHER",
                response.getErrorMessage()
        );
    }

    private DatasetVersion version(String id, Integer versionNo, String label, String status, String description, Instant createdAt) {
        return version(id, "dataset-asset-1", versionNo, label, status, description, createdAt);
    }

    private DatasetVersion version(
            String id,
            String assetId,
            Integer versionNo,
            String label,
            String status,
            String description,
            Instant createdAt
    ) {
        DatasetVersion version = new DatasetVersion();
        version.setId(id);
        version.setAssetId(assetId);
        version.setVersionNo(versionNo);
        version.setVersion(label);
        version.setVersionLabel(label);
        version.setStatus(status);
        version.setDescription(description);
        version.setFileName("data.zip");
        version.setStoragePath("users/7/datasets/dataset-asset-1/" + label + "/data.zip");
        version.setSizeBytes(1024L);
        version.setOwnerUserId(7);
        version.setCreatedAt(createdAt);
        return version;
    }

    private DatasetAsset asset(String id, String name) {
        DatasetAsset asset = new DatasetAsset();
        asset.setId(id);
        asset.setName(name);
        asset.setType("CV");
        asset.setOwnerUserId(7);
        asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return asset;
    }
}
