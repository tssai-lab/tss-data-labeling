package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ModelCodePreviewService;
import com.tss.platform.service.ModelVersionLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelControllerListTest {

    @Test
    void listUsesRepositoryPagingAndFilteringInsteadOfFullMemoryScan() {
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        ModelCodePreviewService codePreviewService = mock(ModelCodePreviewService.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelController controller = new ModelController(
                assetRepo,
                versionRepo,
                codePreviewService,
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        ModelAsset asset = new ModelAsset();
        asset.setId("asset-1");
        asset.setName("Detector");
        asset.setType("CV");
        asset.setRemark("detector model");
        asset.setOwnerUserId(7);
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId(asset.getId());
        version.setVersion("v1");
        version.setFileName("detector.zip");
        version.setStoragePath("users/7/models/internal/detector.zip");
        version.setStatus("READY");
        version.setOwnerUserId(7);
        version.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));

        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(versionRepo.searchVisibleCatalog(eq(7), eq("CV"), eq("%det%"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(version),
                        PageRequest.of(0, 20),
                        37
                ));
        when(assetRepo.findAllById(any())).thenReturn(List.of(asset));

        ApiResponse<Map<String, Object>> response =
                controller.list("CV", " det ", 1, null, 20);

        assertTrue(response.isSuccess());
        Map<String, Object> payload = response.getData();
        assertEquals(37L, payload.get("total"));
        assertEquals(1, payload.get("page"));
        assertEquals(20, payload.get("pageSize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data =
                (List<Map<String, Object>>) payload.get("data");
        assertEquals(1, data.size());
        assertEquals("model-ver-1", data.get(0).get("id"));
        assertEquals("Detector", data.get(0).get("name"));
        assertFalse(data.get(0).containsKey("storagePath"));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(versionRepo).searchVisibleCatalog(eq(7), eq("CV"), eq("%det%"), pageableCaptor.capture());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(20, pageableCaptor.getValue().getPageSize());
        verify(versionRepo, never()).findByDeletedFalse();
        verify(assetRepo, never()).findByDeletedFalse();
        verify(assetRepo, never()).findByOwnerUserIdAndDeletedFalse(any());
    }

    @Test
    void listEscapesLiteralLikeCharactersAndTreatsBlankKeywordAsNoFilter() {
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelController controller = new ModelController(
                assetRepo,
                versionRepo,
                mock(ModelCodePreviewService.class),
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        Pageable pageable = PageRequest.of(0, 10);
        when(authContext.isAdmin()).thenReturn(false);
        when(authContext.currentUserId()).thenReturn(7);
        when(versionRepo.searchVisibleCatalog(
                eq(7),
                eq("CV"),
                eq("%100!%!_\\!!%"),
                any(Pageable.class)
        )).thenReturn(Page.empty(pageable));
        when(versionRepo.searchVisibleCatalog(
                eq(7),
                eq("CV"),
                isNull(),
                any(Pageable.class)
        )).thenReturn(Page.empty(pageable));

        controller.list("CV", " 100%_\\! ", 1, null, 10);
        controller.list("CV", "   ", 1, null, 10);

        verify(versionRepo).searchVisibleCatalog(
                eq(7),
                eq("CV"),
                eq("%100!%!_\\!!%"),
                any(Pageable.class)
        );
        verify(versionRepo).searchVisibleCatalog(
                eq(7),
                eq("CV"),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void trainingCandidateFilterRunsBeforeRepositoryPagination() {
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelController controller = new ModelController(
                assetRepo,
                versionRepo,
                mock(ModelCodePreviewService.class),
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        Pageable pageable = PageRequest.of(1, 20);
        when(authContext.isAdmin()).thenReturn(true);
        when(versionRepo.searchVisibleTrainingCandidates(
                isNull(),
                isNull(),
                isNull(),
                eq(List.of("HF_MODEL_ARCHIVE", "WEIGHT_ARCHIVE")),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(), pageable, 125));

        ApiResponse<Map<String, Object>> response = controller.list(
                null,
                null,
                2,
                null,
                20,
                List.of(" HF_MODEL_ARCHIVE ", "WEIGHT_ARCHIVE", "HF_MODEL_ARCHIVE")
        );

        assertTrue(response.isSuccess());
        assertEquals(125L, response.getData().get("total"));
        verify(versionRepo).searchVisibleTrainingCandidates(
                isNull(),
                isNull(),
                isNull(),
                eq(List.of("HF_MODEL_ARCHIVE", "WEIGHT_ARCHIVE")),
                any(Pageable.class)
        );
        verify(versionRepo, never()).searchVisibleCatalog(
                any(),
                any(),
                any(),
                any(Pageable.class)
        );
    }

    @Test
    void detailDoesNotExposeInternalStoragePath() {
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelController controller = new ModelController(
                assetRepo,
                versionRepo,
                mock(ModelCodePreviewService.class),
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        ModelAsset asset = new ModelAsset();
        asset.setId("asset-1");
        asset.setName("Detector");
        asset.setOwnerUserId(7);
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId(asset.getId());
        version.setStoragePath("users/7/models/internal/detector.zip");
        version.setOwnerUserId(7);
        when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                .thenReturn(java.util.Optional.of(version));
        when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                .thenReturn(java.util.Optional.of(asset));
        when(authContext.canAccessOwner(7)).thenReturn(true);

        ApiResponse<Map<String, Object>> response = controller.detail(version.getId());

        assertTrue(response.isSuccess());
        assertFalse(response.getData().containsKey("storagePath"));
    }
}
