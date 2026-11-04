package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.v2.V2DatasetPreviewDescriptor;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2DatasetPreviewDescriptorServiceTest {

    @Test
    void leRobotKeepsArchivePreview() {
        Fixture fixture = new Fixture("LEROBOT");

        V2DatasetPreviewDescriptor descriptor = fixture.service.describe(fixture.version.getId());

        assertEquals("ARCHIVE", descriptor.getMode());
        assertTrue(descriptor.getCapabilities().contains("LIST_FILES"));
        assertEquals(
                "/api/dataset/preview/files?id=version-1",
                descriptor.getLinks().get("items")
        );
    }

    @Test
    void mapsCvAndNlpToArchivePreview() {
        Fixture fixture = new Fixture("CV");

        V2DatasetPreviewDescriptor descriptor =
                fixture.service.describe(fixture.version.getId());

        assertEquals("ARCHIVE", descriptor.getMode());
        assertTrue(descriptor.getCapabilities().contains("LIST_FILES"));
        assertEquals(
                "/api/dataset/preview/files?id=version-1",
                descriptor.getLinks().get("items")
        );
    }

    @Test
    void mapsPointCloudToPointCloudPreview() {
        Fixture fixture = new Fixture("POINT_CLOUD");

        V2DatasetPreviewDescriptor descriptor =
                fixture.service.describe(fixture.version.getId());

        assertEquals("POINT_CLOUD", descriptor.getMode());
        assertEquals(
                "/api/dataset/point-cloud/preview?id=version-1",
                descriptor.getLinks().get("items")
        );
    }

    @Test
    void mapsMultimodalToSampleGallery() {
        Fixture fixture = new Fixture("MULTIMODAL");

        V2DatasetPreviewDescriptor descriptor =
                fixture.service.describe(fixture.version.getId());

        assertEquals("SAMPLE_GALLERY", descriptor.getMode());
        assertTrue(descriptor.getCapabilities().contains("LIST_SAMPLES"));
        assertEquals(
                "/api/dataset-versions/version-1/samples",
                descriptor.getLinks().get("items")
        );
    }

    @Test
    void previewDescriptorUsesStableLinksOnly() throws Exception {
        Fixture fixture = new Fixture("MULTIMODAL");
        fixture.version.setStoragePath("users/7/datasets/asset-1/v1/data.zip");

        V2DatasetPreviewDescriptor descriptor =
                fixture.service.describe(fixture.version.getId());

        String json = new ObjectMapper().writeValueAsString(descriptor);
        assertEquals("SAMPLE_GALLERY", descriptor.getMode());
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("bucket"));
        assertFalse(json.contains("packageId"));
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final V2DatasetPreviewDescriptorService service =
                new V2DatasetPreviewDescriptorService(versionRepo, assetRepo, authContext);
        private final DatasetVersion version = version();

        private Fixture(String type) {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setType(type);
            asset.setOwnerUserId(7);
            asset.setDeleted(false);
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("version-1");
            value.setAssetId("asset-1");
            value.setStatus("READY");
            value.setDeleted(false);
            return value;
        }
    }
}
