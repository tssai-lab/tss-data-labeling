package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.ModelVersionUpdateRequest;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ModelVersionLifecycleService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelVersionCrudLifecycleTest {

    @Test
    void updateStatusAllowsDeprecatingModelVersion() {
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelVersionLifecycleService lifecycleService = mock(ModelVersionLifecycleService.class);
        ModelVersionCrudController controller = new ModelVersionCrudController(
                versionRepo,
                assetRepo,
                authContext,
                lifecycleService
        );
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId("model-asset-1");
        version.setStatus("READY");
        version.setOwnerUserId(7);
        version.setPublishedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ModelAsset asset = new ModelAsset();
        asset.setId("model-asset-1");
        asset.setOwnerUserId(7);
        when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                .thenReturn(Optional.of(asset));
        when(lifecycleService.retire(version.getId(), "DEPRECATED")).thenAnswer(invocation -> {
            version.setStatus("DEPRECATED");
            return version;
        });

        ApiResponse<ModelVersion> response = controller.updateStatus(
                version.getId(),
                Map.of("status", "DEPRECATED")
        );

        assertTrue(response.isSuccess());
        assertEquals("DEPRECATED", response.getData().getStatus());
        verify(lifecycleService).retire(version.getId(), "DEPRECATED");
    }

    @Test
    void adminUpdatePreservesExistingStorageMetadataWhenBodyOmitsIt() {
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelVersionCrudController controller = new ModelVersionCrudController(
                versionRepo,
                assetRepo,
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId("model-asset-1");
        version.setVersion("v1");
        version.setStatus("READY");
        version.setFileName("weights.pt");
        version.setStoragePath("users/7/models/model-asset-1/v1/weights.pt");
        version.setSizeBytes(1024L);
        version.setOwnerUserId(7);
        ModelAsset asset = new ModelAsset();
        asset.setId("model-asset-1");
        asset.setOwnerUserId(7);
        ModelVersionUpdateRequest body = new ModelVersionUpdateRequest();
        body.setVersion("v1");
        body.setDescription("updated description");
        when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                .thenReturn(Optional.of(version));
        when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                .thenReturn(Optional.of(asset));
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(versionRepo.save(version)).thenReturn(version);

        ApiResponse<ModelVersion> response = controller.update(version.getId(), body);

        assertTrue(response.isSuccess());
        assertEquals("weights.pt", response.getData().getFileName());
        assertEquals("users/7/models/model-asset-1/v1/weights.pt", response.getData().getStoragePath());
        assertEquals(1024L, response.getData().getSizeBytes());
        assertEquals("updated description", response.getData().getDescription());
    }

    @Test
    void updateRejectsClientSuppliedArtifactSpecificationEvidence() {
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        ModelVersionCrudController controller = new ModelVersionCrudController(
                versionRepo,
                assetRepo,
                authContext,
                mock(ModelVersionLifecycleService.class)
        );
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId("model-asset-1");
        version.setOwnerUserId(7);
        ModelVersionUpdateRequest body = new ModelVersionUpdateRequest();
        body.setVersion("v1");
        body.setArtifactSpecId("model.cv.yolo-weight/v1");
        when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                .thenReturn(Optional.of(version));
        when(authContext.canAccessOwner(7)).thenReturn(true);

        ApiResponse<ModelVersion> response = controller.update(version.getId(), body);

        assertFalse(response.isSuccess());
        verify(versionRepo, never()).save(any());
    }
}
