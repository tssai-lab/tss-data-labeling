package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetConsumerManifest;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2DatasetConsumerManifestServiceTest {

    @Test
    void returnsPagedReadyManifestWithoutInternalStorageFields() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asset.setType("MULTIMODAL");
        fixture.version.setStatus("READY");
        fixture.sample.setExternalId("scene-001");
        fixture.data.setOriginalPath("scene-001/front.jpg");
        fixture.data.setPackageId("dataset-pkg-1");
        fixture.data.setZipDataOffset(128L);
        fixture.annotation.setOriginalPath("scene-001/front.json");
        fixture.annotation.setPackageId("dataset-pkg-1");
        fixture.stub();

        V2DatasetConsumerManifest manifest =
                fixture.service.get("dataset-ver-ready", 1, 100);

        assertEquals("dataset-ver-ready", manifest.getDatasetVersionId());
        assertEquals("dataset-asset-1", manifest.getDatasetId());
        assertEquals("MULTIMODAL", manifest.getType());
        assertEquals("READY", manifest.getStatus());
        assertEquals(1, manifest.getSamples().size());
        assertEquals("scene-001", manifest.getSamples().get(0).getExternalId());
        assertEquals(
                "/api/dataset-sample-data/data-1/preview",
                manifest.getSamples().get(0).getData().get(0).getPreviewUrl()
        );
        assertEquals(
                "/api/dataset-sample-data/data-1/download",
                manifest.getSamples().get(0).getData().get(0).getDownloadUrl()
        );
        assertEquals(
                "/api/dataset-annotations/ann-1/download",
                manifest.getSamples().get(0).getAnnotations().get(0).getDownloadUrl()
        );

        String json = fixture.objectMapper.writeValueAsString(manifest);
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("originalPath"));
        assertFalse(json.contains("packageId"));
        assertFalse(json.contains("zipDataOffset"));
        assertFalse(json.contains("ownerUserId"));
    }

    @Test
    void rejectsDraftVersionForConsumerManifest() {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("DRAFT");
        fixture.stub();

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.get("dataset-ver-ready", 1, 100)
        );

        assertEquals("DATASET_NOT_READY", error.getErrorCode());
    }

    @Test
    void rejectsReadyVersionWhenNoConsumableSamplesCanBeBuilt() {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("READY");
        fixture.stub();
        when(fixture.sampleRepo.countByDatasetVersionIdAndDeletedFalse(fixture.version.getId()))
                .thenReturn(0L);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.get("dataset-ver-ready", 1, 100)
        );

        assertEquals("DATASET_NOT_CONSUMABLE", error.getErrorCode());
    }

    @Test
    void lazilyIndexesLegacyReadySingleModalVersion() {
        Fixture fixture = new Fixture();
        fixture.asset.setType("NLP");
        fixture.version.setStatus("READY");
        fixture.stub();
        when(fixture.sampleRepo.countByDatasetVersionIdAndDeletedFalse(fixture.version.getId()))
                .thenReturn(0L, 1L);
        SingleModalDatasetIndexer indexer = mock(SingleModalDatasetIndexer.class);
        when(indexer.ensureIndexed(fixture.version.getId()))
                .thenReturn(SingleModalDatasetIndexer.EnsureResult.success(1));
        fixture.service.setSingleModalDatasetIndexer(indexer);

        V2DatasetConsumerManifest manifest =
                fixture.service.get("dataset-ver-ready", 1, 100);

        assertEquals(1, manifest.getTotalSamples());
        assertEquals(1, manifest.getSamples().size());
    }

    private static final class Fixture {
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
        private final AuthContext authContext = mock(AuthContext.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final V2DatasetConsumerManifestService service =
                new V2DatasetConsumerManifestService(
                        versionRepo,
                        assetRepo,
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        authContext
                );
        private final DatasetAsset asset = asset();
        private final DatasetVersion version = version();
        private final DatasetSample sample = sample();
        private final DatasetSampleData data = data();
        private final DatasetAnnotation annotation = annotation();

        private void stub() {
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId()))
                    .thenReturn(true);
            when(sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId()))
                    .thenReturn(1L);
            when(sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                    eq(version.getId()),
                    any(Pageable.class)
            )).thenReturn(new PageImpl<>(
                    List.of(sample),
                    PageRequest.of(0, 100),
                    1
            ));
            when(dataRepo.findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                    eq(version.getId()),
                    eq(List.of(sample.getId()))
            )).thenReturn(List.of(data));
            when(annotationRepo.findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                    eq(version.getId()),
                    eq(List.of(sample.getId()))
            )).thenReturn(List.of(annotation));
        }

        private static DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("dataset-asset-1");
            value.setName("multimodal");
            value.setType("MULTIMODAL");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }

        private static DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("dataset-ver-ready");
            value.setAssetId("dataset-asset-1");
            value.setVersion("v1");
            value.setVersionLabel("v1");
            value.setVersionNo(1);
            value.setStatus("READY");
            value.setStoragePath("users/7/datasets/a/v1/data.zip");
            value.setDeleted(false);
            return value;
        }

        private static DatasetSample sample() {
            DatasetSample value = new DatasetSample();
            value.setId("sample-1");
            value.setDatasetVersionId("dataset-ver-ready");
            value.setExternalId("scene-001");
            value.setSampleIndex(0);
            value.setTags(Map.of("split", "train"));
            value.setMetadata(Map.of("weather", "clear"));
            value.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            value.setDeleted(false);
            return value;
        }

        private static DatasetSampleData data() {
            DatasetSampleData value = new DatasetSampleData();
            value.setId("data-1");
            value.setSampleId("sample-1");
            value.setDatasetVersionId("dataset-ver-ready");
            value.setDataType("IMAGE");
            value.setSensor("front");
            value.setChannel("rgb");
            value.setSeq(0);
            value.setFormat("jpg");
            value.setOriginalPath("scene-001/front.jpg");
            value.setFileName("front.jpg");
            value.setSizeBytes(12345L);
            value.setChecksum("sha256:abc");
            value.setContentType("image/jpeg");
            value.setCreatedAt(Instant.parse("2026-01-01T00:00:01Z"));
            return value;
        }

        private static DatasetAnnotation annotation() {
            DatasetAnnotation value = new DatasetAnnotation();
            value.setId("ann-1");
            value.setSampleId("sample-1");
            value.setSampleDataId("data-1");
            value.setDatasetVersionId("dataset-ver-ready");
            value.setAnnotationType("bbox");
            value.setFormat("json");
            value.setOriginalPath("scene-001/front.json");
            value.setFileName("front.json");
            value.setSizeBytes(456L);
            value.setChecksum("sha256:def");
            value.setContentType("application/json");
            value.setCreatedAt(Instant.parse("2026-01-01T00:00:02Z"));
            return value;
        }
    }
}
