package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.DatasetMultimodalExternalIdSampleDto;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetSampleListItemDto;
import com.tss.platform.dto.PageResponse;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SampleServiceTest {

    @Test
    void pagesSamplesForReadyVersionWithStableSortAndPageSizeCap() {
        Fixture fixture = new Fixture();
        fixture.stubAuthorizedReadyVersion();
        DatasetSample sample = fixture.sample();
        when(fixture.sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                eq(fixture.version.getId()),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(sample)));

        PageResponse<DatasetSampleListItemDto> result =
                fixture.service.listSamples(fixture.version.getId(), 0, 500);

        assertEquals(1, result.getPage());
        assertEquals(100, result.getPageSize());
        assertEquals(1, result.getTotal());
        assertEquals(sample.getId(), result.getData().get(0).getSampleId());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.sampleRepo).findByDatasetVersionIdAndDeletedFalse(
                eq(fixture.version.getId()),
                pageable.capture()
        );
        assertEquals("sampleIndex: ASC,createdAt: ASC,id: ASC", pageable.getValue().getSort().toString());
    }

    @Test
    void rejectsDraftVersionBeforeQueryingSamples() {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("DRAFT");
        fixture.stubAuthorizedVersion();

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.listSamples(fixture.version.getId(), 1, 20)
        );

        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalse(any(), any(Pageable.class));
    }

    @Test
    void rejectsDeletedVersionBeforeQueryingSamples() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo.findByIdAndDeletedFalse(fixture.version.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.listSamples(fixture.version.getId(), 1, 20)
        );

        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalse(any(), any(Pageable.class));
    }

    @Test
    void returnsSampleDetailWithDataAndAnnotations() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        DatasetSampleData data = fixture.data(sample);
        DatasetAnnotation annotation = fixture.annotation(sample, data);
        fixture.stubAuthorizedSample(sample);
        when(fixture.dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                sample.getId(),
                fixture.version.getId()
        )).thenReturn(List.of(data));
        when(fixture.annotationRepo.findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                sample.getId(),
                fixture.version.getId()
        )).thenReturn(List.of(annotation));

        DatasetSampleDetailDto result = fixture.service.getSample(sample.getId());

        assertEquals(sample.getId(), result.getSampleId());
        assertEquals(data.getId(), result.getData().get(0).getSampleDataId());
        assertEquals(annotation.getId(), result.getAnnotations().get(0).getAnnotationId());
        assertEquals(data.getId(), result.getAnnotations().get(0).getSampleDataId());
    }

    @Test
    void returnsSampleDataList() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        DatasetSampleData data = fixture.data(sample);
        fixture.stubAuthorizedSample(sample);
        when(fixture.dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                sample.getId(),
                fixture.version.getId()
        )).thenReturn(List.of(data));

        List<DatasetSampleDataDto> result = fixture.service.listSampleData(sample.getId());

        assertEquals(1, result.size());
        assertEquals("video/mp4", result.get(0).getContentType());
        assertEquals("mp4", result.get(0).getFormat());
    }

    @Test
    void rejectsAnotherUsersSampleWithoutReadingChildren() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        when(fixture.sampleRepo.findByIdAndDeletedFalse(sample.getId())).thenReturn(Optional.of(sample));
        when(fixture.versionRepo.findByIdAndDeletedFalse(fixture.version.getId()))
                .thenReturn(Optional.of(fixture.version));
        when(fixture.assetRepo.findByIdAndDeletedFalse(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId())).thenReturn(false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.getSample(sample.getId())
        );

        assertEquals("dataset sample not found or no permission", error.getMessage());
        verify(fixture.dataRepo, never())
                .findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(any(), any());
        verify(fixture.annotationRepo, never())
                .findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(any(), any());
    }

    @Test
    void responseDtosDoNotExposeStorageZipOrUnavailableLinks() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        DatasetSampleData data = fixture.data(sample);
        DatasetAnnotation annotation = fixture.annotation(sample, data);
        fixture.stubAuthorizedSample(sample);
        when(fixture.dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                sample.getId(),
                fixture.version.getId()
        )).thenReturn(List.of(data));
        when(fixture.annotationRepo.findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                sample.getId(),
                fixture.version.getId()
        )).thenReturn(List.of(annotation));

        String json = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(fixture.service.getSample(sample.getId()));

        for (String forbidden : List.of(
                "storagePath",
                "bucket",
                "originalPath",
                "zipEntryOffset",
                "zipDataOffset",
                "compressedSize",
                "crc32",
                "objectName",
                "previewUrl",
                "downloadUrl"
        )) {
            assertFalse(json.contains(forbidden), "response contains forbidden field: " + forbidden);
        }
        assertTrue(json.contains("\"sampleDataId\":\"data-1\""));
    }

    @Test
    void findsExternalIdAcrossReadyVersionsWithStableLinksAndFields() throws Exception {
        Fixture fixture = new Fixture();
        DatasetVersion secondVersion = Fixture.version("version-2", "asset-2", "READY");
        DatasetAsset secondAsset = Fixture.asset("asset-2", 9);
        DatasetSample firstSample = fixture.sample(
                "sample-1",
                fixture.version.getId(),
                "scene-1",
                1
        );
        DatasetSample secondSample = fixture.sample(
                "sample-2",
                secondVersion.getId(),
                "scene-1",
                2
        );
        DatasetSampleData firstData = fixture.data("data-1", firstSample);
        DatasetSampleData secondData = fixture.data("data-2", secondSample);
        DatasetAnnotation firstAnnotation =
                fixture.annotation("annotation-1", firstSample, firstData);
        DatasetAnnotation secondAnnotation =
                fixture.annotation("annotation-2", secondSample, secondData);

        when(fixture.versionRepo.findByIdInAndDeletedFalse(List.of("version-1", "version-2")))
                .thenReturn(List.of(fixture.version, secondVersion));
        when(fixture.assetRepo.findByIdAndDeletedFalse(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.assetRepo.findByIdAndDeletedFalse(secondAsset.getId()))
                .thenReturn(Optional.of(secondAsset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(true);
        when(fixture.authContext.canAccessOwner(secondAsset.getOwnerUserId()))
                .thenReturn(true);
        when(fixture.sampleRepo.findByDatasetVersionIdInAndExternalIdAndDeletedFalse(
                eq(List.of("version-1", "version-2")),
                eq("scene-1"),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(firstSample, secondSample)));
        when(fixture.dataRepo.findBySampleIdInOrderBySampleIdAscSeqAscIdAsc(
                List.of(firstSample.getId(), secondSample.getId())
        )).thenReturn(List.of(firstData, secondData));
        when(fixture.annotationRepo.findBySampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                List.of(firstSample.getId(), secondSample.getId())
        )).thenReturn(List.of(firstAnnotation, secondAnnotation));

        PageResponse<DatasetMultimodalExternalIdSampleDto> result =
                fixture.service.findMultimodalByExternalId(
                        " scene-1 ",
                        List.of("version-1, version-2"),
                        1,
                        20
                );

        assertEquals(2, result.getTotal());
        assertEquals("version-1", result.getData().get(0).getDatasetVersionId());
        assertEquals("sample-1", result.getData().get(0).getSampleId());
        assertEquals("/api/dataset-sample-data/data-1/preview",
                result.getData().get(0).getData().get(0).getPreviewUrl());
        assertEquals("/api/dataset-sample-data/data-1/download",
                result.getData().get(0).getData().get(0).getDownloadUrl());
        assertEquals("/api/dataset-annotations/annotation-1/download",
                result.getData().get(0).getAnnotations().get(0).getDownloadUrl());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.sampleRepo).findByDatasetVersionIdInAndExternalIdAndDeletedFalse(
                eq(List.of("version-1", "version-2")),
                eq("scene-1"),
                pageable.capture()
        );
        assertEquals(
                "datasetVersionId: ASC,sampleIndex: ASC,createdAt: ASC,id: ASC",
                pageable.getValue().getSort().toString()
        );

        String json = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(result);
        for (String forbidden : List.of(
                "storagePath",
                "bucket",
                "objectName",
                "originalPath",
                "zipEntryOffset",
                "zipDataOffset",
                "compressedSize",
                "packageId"
        )) {
            assertFalse(json.contains(forbidden), "response contains forbidden field: " + forbidden);
        }
        assertTrue(json.contains("\"datasetVersionId\":\"version-1\""));
        assertTrue(json.contains("\"previewUrl\":\"/api/dataset-sample-data/data-1/preview\""));
    }

    @Test
    void rejectsUnavailableExternalIdVersionWithoutLeakingWhichVersion() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo.findByIdInAndDeletedFalse(List.of("version-1", "version-2")))
                .thenReturn(List.of(fixture.version));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.findMultimodalByExternalId(
                        "scene-1",
                        List.of("version-1", "version-2"),
                        1,
                        20
                )
        );

        assertEquals("dataset version not found or no permission", error.getMessage());
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdInAndExternalIdAndDeletedFalse(any(), any(), any());
    }

    @Test
    void rejectsUnauthorizedExternalIdVersionBeforeQueryingSamples() {
        Fixture fixture = new Fixture();
        when(fixture.versionRepo.findByIdInAndDeletedFalse(List.of("version-1")))
                .thenReturn(List.of(fixture.version));
        when(fixture.assetRepo.findByIdAndDeletedFalse(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.findMultimodalByExternalId(
                        "scene-1",
                        List.of("version-1"),
                        1,
                        20
                )
        );

        assertEquals("dataset version not found or no permission", error.getMessage());
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdInAndExternalIdAndDeletedFalse(any(), any(), any());
    }

    private static final class Fixture {
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo = mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final SampleService service = new SampleService(
                sampleRepo,
                dataRepo,
                annotationRepo,
                versionRepo,
                assetRepo,
                authContext
        );

        private void stubAuthorizedReadyVersion() {
            version.setStatus("READY");
            stubAuthorizedVersion();
        }

        private void stubAuthorizedVersion() {
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private void stubAuthorizedSample(DatasetSample sample) {
            stubAuthorizedReadyVersion();
            when(sampleRepo.findByIdAndDeletedFalse(sample.getId())).thenReturn(Optional.of(sample));
        }

        private DatasetSample sample() {
            return sample("sample-1", version.getId(), "scene-1", 3);
        }

        private DatasetSample sample(
                String sampleId,
                String datasetVersionId,
                String externalId,
                Integer sampleIndex
        ) {
            DatasetSample sample = new DatasetSample();
            sample.setId(sampleId);
            sample.setDatasetVersionId(datasetVersionId);
            sample.setExternalId(externalId);
            sample.setSampleIndex(sampleIndex);
            sample.setTags(Map.of("weather", "sunny"));
            sample.setMetadata(Map.of("split", "train"));
            sample.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            sample.setDeleted(false);
            return sample;
        }

        private DatasetSampleData data(DatasetSample sample) {
            return data("data-1", sample);
        }

        private DatasetSampleData data(String dataId, DatasetSample sample) {
            DatasetSampleData data = new DatasetSampleData();
            data.setId(dataId);
            data.setSampleId(sample.getId());
            data.setDatasetVersionId(sample.getDatasetVersionId());
            data.setDataType("VIDEO");
            data.setSensor("front");
            data.setChannel("rgb");
            data.setSeq(0);
            data.setFormat("mp4");
            data.setOriginalPath("private/video.mp4");
            data.setFileName("video.mp4");
            data.setSizeBytes(1024L);
            data.setChecksum("sha256:test");
            data.setContentType("video/mp4");
            data.setZipEntryOffset(11L);
            data.setZipDataOffset(22L);
            data.setCompressedSize(900L);
            data.setCrc32(33L);
            data.setMetadata(Map.of("codec", "h264"));
            data.setCreatedAt(Instant.parse("2026-06-01T00:00:01Z"));
            return data;
        }

        private DatasetAnnotation annotation(DatasetSample sample, DatasetSampleData data) {
            return annotation("annotation-1", sample, data);
        }

        private DatasetAnnotation annotation(
                String annotationId,
                DatasetSample sample,
                DatasetSampleData data
        ) {
            DatasetAnnotation annotation = new DatasetAnnotation();
            annotation.setId(annotationId);
            annotation.setSampleId(sample.getId());
            annotation.setSampleDataId(data.getId());
            annotation.setDatasetVersionId(sample.getDatasetVersionId());
            annotation.setAnnotationType("TRACK");
            annotation.setFormat("json");
            annotation.setOriginalPath("private/annotation.json");
            annotation.setFileName("annotation.json");
            annotation.setSizeBytes(100L);
            annotation.setChecksum("sha256:annotation");
            annotation.setContentType("application/json");
            annotation.setZipEntryOffset(44L);
            annotation.setZipDataOffset(55L);
            annotation.setCompressedSize(80L);
            annotation.setCrc32(66L);
            annotation.setMetadata(Map.of("source", "manual"));
            annotation.setCreatedAt(Instant.parse("2026-06-01T00:00:02Z"));
            return annotation;
        }

        private static DatasetVersion version() {
            return version("version-1", "asset-1", "READY");
        }

        private static DatasetVersion version(String versionId, String assetId, String status) {
            DatasetVersion version = new DatasetVersion();
            version.setId(versionId);
            version.setAssetId(assetId);
            version.setStatus(status);
            version.setDeleted(false);
            return version;
        }

        private static DatasetAsset asset() {
            return asset("asset-1", 7);
        }

        private static DatasetAsset asset(String assetId, Integer ownerUserId) {
            DatasetAsset asset = new DatasetAsset();
            asset.setId(assetId);
            asset.setOwnerUserId(ownerUserId);
            asset.setDeleted(false);
            return asset;
        }
    }
}
