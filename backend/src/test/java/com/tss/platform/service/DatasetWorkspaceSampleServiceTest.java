package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetWorkspaceSampleListItemDto;
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

class DatasetWorkspaceSampleServiceTest {

    @Test
    void pagesMaterializedDraftSamplesWithStableSortAndPageSizeCap() {
        Fixture fixture = new Fixture();
        fixture.stubAuthorizedDraftVersion();
        DatasetSample sample = fixture.sample();
        when(fixture.sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                eq(fixture.version.getId()),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(sample)));

        PageResponse<DatasetWorkspaceSampleListItemDto> result =
                fixture.service.listSamples(fixture.version.getId(), 0, 500, false);

        assertEquals(1, result.getPage());
        assertEquals(100, result.getPageSize());
        assertEquals(1, result.getTotal());
        assertEquals(sample.getId(), result.getData().get(0).getSampleId());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.sampleRepo).findByDatasetVersionIdAndDeletedFalse(
                eq(fixture.version.getId()),
                pageable.capture()
        );
        assertEquals(
                "sampleIndex: ASC,createdAt: ASC,id: ASC",
                pageable.getValue().getSort().toString()
        );
    }

    @Test
    void includeDeletedReturnsSoftDeletedDraftSamplesWithSafeState() {
        Fixture fixture = new Fixture();
        fixture.stubAuthorizedDraftVersion();
        DatasetSample deleted = fixture.sample();
        deleted.setDeleted(true);
        deleted.setDeletedAt(Instant.parse("2026-06-12T10:00:00Z"));
        when(fixture.sampleRepo.findByDatasetVersionId(
                eq(fixture.version.getId()),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(deleted)));

        PageResponse<DatasetWorkspaceSampleListItemDto> result =
                fixture.service.listSamples(fixture.version.getId(), 1, 20, true);

        assertEquals(1, result.getTotal());
        assertTrue(result.getData().get(0).getDeleted());
        assertEquals(deleted.getDeletedAt(), result.getData().get(0).getDeletedAt());
        verify(fixture.sampleRepo).findByDatasetVersionId(
                eq(fixture.version.getId()),
                any(Pageable.class)
        );
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalse(any(), any(Pageable.class));
    }

    @Test
    void returnsDraftSampleDetailWithMaterializedDataAndAnnotations() {
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
        assertEquals(fixture.version.getId(), result.getDatasetVersionId());
        assertEquals(data.getId(), result.getData().get(0).getSampleDataId());
        assertEquals(annotation.getId(), result.getAnnotations().get(0).getAnnotationId());
        assertEquals(data.getId(), result.getAnnotations().get(0).getSampleDataId());
    }

    @Test
    void returnsDraftSampleDataList() {
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
        assertEquals(data.getId(), result.get(0).getSampleDataId());
        assertEquals("video/mp4", result.get(0).getContentType());
    }

    @Test
    void rejectsReadyVersionBeforeQueryingWorkspaceSamples() {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("READY");
        fixture.stubAuthorizedVersion();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.listSamples(
                        fixture.version.getId(),
                        1,
                        20,
                        false
                )
        );

        assertEquals(
                "dataset workspace version not found or no permission",
                error.getMessage()
        );
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalse(any(), any(Pageable.class));
    }

    @Test
    void deletedDraftSampleIsNotAvailable() {
        Fixture fixture = new Fixture();
        when(fixture.sampleRepo.findByIdAndDeletedFalse("deleted-sample"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.getSample("deleted-sample")
        );

        assertEquals(
                "dataset workspace sample not found or no permission",
                error.getMessage()
        );
        verify(fixture.versionRepo, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void rejectsAnotherUsersDraftSampleWithoutReadingChildren() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        when(fixture.sampleRepo.findByIdAndDeletedFalse(sample.getId()))
                .thenReturn(Optional.of(sample));
        when(fixture.versionRepo.findByIdAndDeletedFalse(fixture.version.getId()))
                .thenReturn(Optional.of(fixture.version));
        when(fixture.assetRepo.findByIdAndDeletedFalse(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.getSample(sample.getId())
        );

        assertEquals(
                "dataset workspace sample not found or no permission",
                error.getMessage()
        );
        verify(fixture.dataRepo, never())
                .findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(any(), any());
        verify(fixture.annotationRepo, never())
                .findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(any(), any());
    }

    @Test
    void responseDtosDoNotExposeStoragePackageZipOrFileLinks() throws Exception {
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
                "packageId",
                "objectName",
                "previewUrl",
                "downloadUrl"
        )) {
            assertFalse(json.contains(forbidden), "response contains forbidden field: " + forbidden);
        }
        assertTrue(json.contains("\"sampleDataId\":\"data-1\""));
    }

    private static final class Fixture {
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final DatasetWorkspaceSampleService service =
                new DatasetWorkspaceSampleService(
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        versionRepo,
                        assetRepo,
                        authContext
                );

        private void stubAuthorizedDraftVersion() {
            version.setStatus("DRAFT");
            stubAuthorizedVersion();
        }

        private void stubAuthorizedVersion() {
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private void stubAuthorizedSample(DatasetSample sample) {
            stubAuthorizedDraftVersion();
            when(sampleRepo.findByIdAndDeletedFalse(sample.getId()))
                    .thenReturn(Optional.of(sample));
        }

        private DatasetSample sample() {
            DatasetSample sample = new DatasetSample();
            sample.setId("sample-1");
            sample.setDatasetVersionId(version.getId());
            sample.setExternalId("scene-1");
            sample.setSampleIndex(3);
            sample.setTags(Map.of("weather", "sunny"));
            sample.setMetadata(Map.of("split", "train"));
            sample.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            sample.setDeleted(false);
            return sample;
        }

        private DatasetSampleData data(DatasetSample sample) {
            DatasetSampleData data = new DatasetSampleData();
            data.setId("data-1");
            data.setSampleId(sample.getId());
            data.setDatasetVersionId(version.getId());
            data.setPackageId("package-1");
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

        private DatasetAnnotation annotation(
                DatasetSample sample,
                DatasetSampleData data
        ) {
            DatasetAnnotation annotation = new DatasetAnnotation();
            annotation.setId("annotation-1");
            annotation.setSampleId(sample.getId());
            annotation.setSampleDataId(data.getId());
            annotation.setDatasetVersionId(version.getId());
            annotation.setPackageId("package-1");
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
            DatasetVersion version = new DatasetVersion();
            version.setId("draft-1");
            version.setAssetId("asset-1");
            version.setStatus("DRAFT");
            version.setDeleted(false);
            return version;
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            asset.setDeleted(false);
            return asset;
        }
    }
}
