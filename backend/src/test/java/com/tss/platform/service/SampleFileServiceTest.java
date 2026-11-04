package com.tss.platform.service;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SampleFileServiceTest {

    @Test
    void previewsPackageBackedDataFromPackageStorage() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "STORED");
        data.setPackageId(fixture.datasetPackage.getId());
        fixture.stubAuthorizedData(data);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadRange(
                fixture.datasetPackage.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        fixture.service.openDataPreview(data.getId());

        verify(fixture.minioService).downloadRange(
                fixture.datasetPackage.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        );
    }

    @Test
    void downloadsPackageBackedDataFromPackageStorage() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "STORED");
        data.setPackageId(fixture.datasetPackage.getId());
        fixture.stubAuthorizedData(data);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadRange(
                fixture.datasetPackage.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        fixture.service.openDataDownload(data.getId());

        verify(fixture.minioService).downloadRange(
                fixture.datasetPackage.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        );
    }

    @Test
    void downloadsPackageBackedAnnotationFromPackageStorage() throws Exception {
        Fixture fixture = new Fixture();
        DatasetAnnotation annotation = fixture.annotation("STORED");
        annotation.setPackageId(fixture.datasetPackage.getId());
        fixture.stubAuthorizedAnnotation(annotation);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadRange(
                fixture.datasetPackage.getStoragePath(),
                annotation.getZipDataOffset(),
                annotation.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        fixture.service.openAnnotationDownload(annotation.getId());

        verify(fixture.minioService).downloadRange(
                fixture.datasetPackage.getStoragePath(),
                annotation.getZipDataOffset(),
                annotation.getCompressedSize()
        );
    }

    @Test
    void downloadsRawDataFromWholeObjectWithoutZipIndex() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("TEXT", null);
        byte[] expected = "raw workspace data".getBytes(StandardCharsets.UTF_8);
        data.setPackageId(fixture.datasetPackage.getId());
        data.setSizeBytes((long) expected.length);
        data.setZipDataOffset(null);
        data.setCompressedSize(null);
        data.setUncompressedSize(null);
        fixture.datasetPackage.setStorageKind("RAW");
        fixture.stubAuthorizedData(data);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadStream(
                fixture.datasetPackage.getStoragePath()
        )).thenReturn(new ByteArrayInputStream(expected));

        SampleFileService.SampleFileStream result =
                fixture.service.openDataDownload(data.getId());

        assertArrayEquals(expected, result.inputStream().readAllBytes());
        assertEquals((long) expected.length, result.sizeBytes());
        verify(fixture.minioService).downloadStream(
                fixture.datasetPackage.getStoragePath()
        );
        verify(fixture.minioService, never()).downloadRange(
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void downloadsRawAnnotationFromWholeObjectWithoutZipIndex() throws Exception {
        Fixture fixture = new Fixture();
        DatasetAnnotation annotation = fixture.annotation(null);
        byte[] expected = "{\"label\":1}".getBytes(StandardCharsets.UTF_8);
        annotation.setPackageId(fixture.datasetPackage.getId());
        annotation.setSizeBytes((long) expected.length);
        annotation.setZipDataOffset(null);
        annotation.setCompressedSize(null);
        annotation.setUncompressedSize(null);
        fixture.datasetPackage.setStorageKind("RAW");
        fixture.stubAuthorizedAnnotation(annotation);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadStream(
                fixture.datasetPackage.getStoragePath()
        )).thenReturn(new ByteArrayInputStream(expected));

        SampleFileService.SampleFileStream result =
                fixture.service.openAnnotationDownload(annotation.getId());

        assertArrayEquals(expected, result.inputStream().readAllBytes());
        verify(fixture.minioService).downloadStream(
                fixture.datasetPackage.getStoragePath()
        );
    }

    @Test
    void previewsRawVideoUsingObjectRange() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("VIDEO", null);
        data.setPackageId(fixture.datasetPackage.getId());
        data.setSizeBytes(1000L);
        data.setZipDataOffset(null);
        data.setCompressedSize(null);
        data.setUncompressedSize(null);
        fixture.datasetPackage.setStorageKind("RAW");
        fixture.stubAuthorizedData(data);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadRange(
                fixture.datasetPackage.getStoragePath(),
                100L,
                100L
        )).thenReturn(new ByteArrayInputStream(new byte[100]));

        SampleFileService.SampleFileStream result =
                fixture.service.openDataPreview(data.getId(), "bytes=100-199");

        assertTrue(result.partial());
        assertEquals(100L, result.rangeStart());
        assertEquals(199L, result.rangeEnd());
        assertEquals(1000L, result.totalSize());
        verify(fixture.minioService).downloadRange(
                fixture.datasetPackage.getStoragePath(),
                100L,
                100L
        );
    }

    @Test
    void previewsPackageBackedStoredVideoRangeFromPackageStorage() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("VIDEO", "STORED");
        data.setPackageId(fixture.datasetPackage.getId());
        data.setZipDataOffset(5000L);
        data.setCompressedSize(1000L);
        data.setUncompressedSize(1000L);
        fixture.stubAuthorizedData(data);
        fixture.stubPackageSource();
        when(fixture.minioService.downloadRange(
                fixture.datasetPackage.getStoragePath(),
                5100L,
                100L
        )).thenReturn(new ByteArrayInputStream(new byte[100]));

        SampleFileService.SampleFileStream result =
                fixture.service.openDataPreview(data.getId(), "bytes=100-199");

        assertTrue(result.partial());
        verify(fixture.minioService).downloadRange(
                fixture.datasetPackage.getStoragePath(),
                5100L,
                100L
        );
    }

    @Test
    void previewsStoredDataFromExactMinioRange() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "STORED");
        byte[] expected = "stored-image".getBytes(StandardCharsets.UTF_8);
        data.setCompressedSize((long) expected.length);
        data.setUncompressedSize((long) expected.length);
        fixture.stubAuthorizedData(data);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(expected));

        SampleFileService.SampleFileStream result = fixture.service.openDataPreview(data.getId());

        assertArrayEquals(expected, result.inputStream().readAllBytes());
        assertEquals("image/png", result.contentType());
        assertEquals(expected.length, result.sizeBytes());
        verify(fixture.minioService).downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        );
    }

    @Test
    void previewsDeflatedDataUsingRawInflater() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("TEXT", "DEFLATED");
        byte[] expected = "raw deflate text".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = rawDeflate(expected);
        data.setCompressedSize((long) compressed.length);
        data.setUncompressedSize((long) expected.length);
        data.setContentType("text/plain");
        fixture.stubAuthorizedData(data);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(compressed));

        SampleFileService.SampleFileStream result = fixture.service.openDataPreview(data.getId());

        assertArrayEquals(expected, result.inputStream().readAllBytes());
    }

    @Test
    void previewsWholeStoredVideoAndKeepsDownloadBehavior() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("VIDEO", "STORED");
        data.setCompressedSize(1000L);
        data.setUncompressedSize(1000L);
        data.setContentType(null);
        fixture.stubAuthorizedData(data);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(new byte[1000]));

        SampleFileService.SampleFileStream preview =
                fixture.service.openDataPreview(data.getId(), null);
        SampleFileService.SampleFileStream download =
                fixture.service.openDataDownload(data.getId());

        assertTrue(preview.rangeSupported());
        assertFalse(preview.partial());
        assertEquals(0L, preview.rangeStart());
        assertEquals(999L, preview.rangeEnd());
        assertEquals(1000L, preview.totalSize());
        assertEquals(1000L, preview.sizeBytes());
        assertEquals("video/mp4", preview.contentType());
        assertNull(download.contentType());
    }

    @Test
    void previewsClosedOpenEndedAndSuffixStoredVideoRanges() throws Exception {
        for (RangeCase rangeCase : new RangeCase[]{
                new RangeCase("bytes=0-99", 0, 99),
                new RangeCase("bytes=100-", 100, 999),
                new RangeCase("bytes=-100", 900, 999)
        }) {
            Fixture fixture = new Fixture();
            DatasetSampleData data = fixture.data("VIDEO", "STORED");
            data.setZipDataOffset(5000L);
            data.setCompressedSize(1000L);
            data.setUncompressedSize(1000L);
            fixture.stubAuthorizedData(data);
            long length = rangeCase.end() - rangeCase.start() + 1;
            when(fixture.minioService.downloadRange(
                    fixture.version.getStoragePath(),
                    data.getZipDataOffset() + rangeCase.start(),
                    length
            )).thenReturn(new ByteArrayInputStream(new byte[(int) length]));

            SampleFileService.SampleFileStream result =
                    fixture.service.openDataPreview(data.getId(), rangeCase.header());

            assertTrue(result.rangeSupported());
            assertTrue(result.partial());
            assertEquals(rangeCase.start(), result.rangeStart());
            assertEquals(rangeCase.end(), result.rangeEnd());
            assertEquals(1000L, result.totalSize());
            assertEquals(length, result.sizeBytes());
            verify(fixture.minioService).downloadRange(
                    fixture.version.getStoragePath(),
                    data.getZipDataOffset() + rangeCase.start(),
                    length
            );
        }
    }

    @Test
    void rejectsDeflatedVideoPreviewButStillAllowsDownload() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("VIDEO", "DEFLATED");
        byte[] compressed = rawDeflate(new byte[]{1, 2, 3});
        data.setCompressedSize((long) compressed.length);
        fixture.stubAuthorizedData(data);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(compressed));

        SampleFileException previewError = assertThrows(
                SampleFileException.class,
                () -> fixture.service.openDataPreview(data.getId(), "bytes=0-1")
        );
        SampleFileService.SampleFileStream download =
                fixture.service.openDataDownload(data.getId());

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, previewError.getStatus());
        assertEquals("DEFLATED video preview is not supported; use download",
                previewError.getMessage());
        assertArrayEquals(new byte[]{1, 2, 3}, download.inputStream().readAllBytes());
    }

    @Test
    void rejectsInvalidStoredVideoMetadataBeforeReadingMinio() throws Exception {
        for (String missingField : new String[]{
                "zipDataOffset",
                "compressedSize",
                "uncompressedSize"
        }) {
            Fixture missing = new Fixture();
            DatasetSampleData data = missing.data("VIDEO", "STORED");
            switch (missingField) {
                case "zipDataOffset" -> data.setZipDataOffset(null);
                case "compressedSize" -> data.setCompressedSize(null);
                case "uncompressedSize" -> data.setUncompressedSize(null);
                default -> throw new IllegalStateException(missingField);
            }
            missing.stubAuthorizedData(data);

            SampleFileException missingError = assertThrows(
                    SampleFileException.class,
                    () -> missing.service.openDataPreview(data.getId(), null)
            );
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missingError.getStatus());
            assertEquals("VIDEO ZIP entry index is incomplete", missingError.getMessage());
            verify(missing.minioService, never())
                    .downloadRange(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }

        Fixture mismatch = new Fixture();
        DatasetSampleData mismatchData = mismatch.data("VIDEO", "STORED");
        mismatchData.setCompressedSize(999L);
        mismatchData.setUncompressedSize(1000L);
        mismatch.stubAuthorizedData(mismatchData);

        SampleFileException mismatchError = assertThrows(
                SampleFileException.class,
                () -> mismatch.service.openDataPreview(mismatchData.getId(), null)
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, mismatchError.getStatus());
        assertEquals("STORED video compressed and uncompressed sizes differ",
                mismatchError.getMessage());
        verify(mismatch.minioService, never())
                .downloadRange(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsMissingAndUnsupportedVideoCompressionMethods() throws Exception {
        for (String compressionMethod : new String[]{null, "", "BZIP2"}) {
            Fixture fixture = new Fixture();
            DatasetSampleData data = fixture.data("VIDEO", compressionMethod);
            fixture.stubAuthorizedData(data);

            SampleFileException error = assertThrows(
                    SampleFileException.class,
                    () -> fixture.service.openDataPreview(data.getId(), null)
            );

            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, error.getStatus());
            verify(fixture.minioService, never())
                    .downloadRange(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Test
    void rejectsMalformedMultiAndUnsatisfiableVideoRanges() {
        for (String range : new String[]{
                "bytes=0-99,200-299",
                "items=0-99",
                "bytes=1000-",
                "bytes=0-1000",
                "bytes=200-100",
                "bytes=-0"
        }) {
            Fixture fixture = new Fixture();
            DatasetSampleData data = fixture.data("VIDEO", "STORED");
            data.setCompressedSize(1000L);
            data.setUncompressedSize(1000L);
            fixture.stubAuthorizedData(data);

            SampleFileException error = assertThrows(
                    SampleFileException.class,
                    () -> fixture.service.openDataPreview(data.getId(), range)
            );

            assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, error.getStatus());
            assertEquals(1000L, error.getRangeTotal());
        }
    }

    @Test
    void rejectsDraftAndDeletedVideoBeforeOpeningRange() {
        Fixture draft = new Fixture();
        DatasetSampleData draftData = draft.data("VIDEO", "STORED");
        draft.version.setStatus("DRAFT");
        draft.stubVersionAndData(draftData);

        Fixture deleted = new Fixture();
        DatasetSampleData deletedData = deleted.data("VIDEO", "STORED");
        when(deleted.dataRepo.findById(deletedData.getId())).thenReturn(Optional.of(deletedData));
        DatasetSample sample = new DatasetSample();
        sample.setId(deletedData.getSampleId());
        sample.setDatasetVersionId(deleted.version.getId());
        sample.setDeleted(false);
        when(deleted.sampleRepo.findByIdAndDeletedFalse(sample.getId()))
                .thenReturn(Optional.of(sample));
        when(deleted.versionRepo.findByIdAndDeletedFalse(deleted.version.getId()))
                .thenReturn(Optional.empty());

        for (SampleFileException error : new SampleFileException[]{
                assertThrows(SampleFileException.class,
                        () -> draft.service.openDataPreview(draftData.getId(), "bytes=0-1")),
                assertThrows(SampleFileException.class,
                        () -> deleted.service.openDataPreview(deletedData.getId(), "bytes=0-1"))
        }) {
            assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
            assertEquals("sample file not found or no permission", error.getMessage());
        }
    }

    @Test
    void rejectsDeletedSamplePreviewAndDownloadsBeforeVersionOrMinioAccess()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("DRAFT");
        DatasetSampleData data = fixture.data("VIDEO", "STORED");
        DatasetAnnotation annotation = fixture.annotation("STORED");
        when(fixture.dataRepo.findById(data.getId())).thenReturn(Optional.of(data));
        when(fixture.annotationRepo.findById(annotation.getId()))
                .thenReturn(Optional.of(annotation));
        when(fixture.sampleRepo.findByIdAndDeletedFalse(data.getSampleId()))
                .thenReturn(Optional.empty());

        for (SampleFileException error : new SampleFileException[]{
                assertThrows(
                        SampleFileException.class,
                        () -> fixture.service.openDataPreview(data.getId(), "bytes=0-1")
                ),
                assertThrows(
                        SampleFileException.class,
                        () -> fixture.service.openDataDownload(data.getId())
                ),
                assertThrows(
                        SampleFileException.class,
                        () -> fixture.service.openAnnotationDownload(annotation.getId())
                )
        }) {
            assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        }
        verify(fixture.sampleRepo, times(3))
                .findByIdAndDeletedFalse(data.getSampleId());
        verify(fixture.versionRepo, never()).findByIdAndDeletedFalse(any());
        verify(fixture.minioService, never()).downloadRange(
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void nonVideoPreviewRetainsD2BehaviorWhenRangeHeaderIsPresent() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "STORED");
        fixture.stubAuthorizedData(data);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                data.getZipDataOffset(),
                data.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        SampleFileService.SampleFileStream result =
                fixture.service.openDataPreview(data.getId(), "bytes=0-1");

        assertFalse(result.rangeSupported());
        assertFalse(result.partial());
        assertNull(result.rangeStart());
        assertArrayEquals(new byte[]{1, 2, 3}, result.inputStream().readAllBytes());
    }

    @Test
    void downloadsAnnotationFromExactRange() throws Exception {
        Fixture fixture = new Fixture();
        DatasetAnnotation annotation = fixture.annotation("DEFLATED");
        byte[] expected = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = rawDeflate(expected);
        annotation.setCompressedSize((long) compressed.length);
        annotation.setUncompressedSize((long) expected.length);
        fixture.stubAuthorizedAnnotation(annotation);
        when(fixture.minioService.downloadRange(
                fixture.version.getStoragePath(),
                annotation.getZipDataOffset(),
                annotation.getCompressedSize()
        )).thenReturn(new ByteArrayInputStream(compressed));

        SampleFileService.SampleFileStream result =
                fixture.service.openAnnotationDownload(annotation.getId());

        assertArrayEquals(expected, result.inputStream().readAllBytes());
        assertEquals("annotation.json", result.fileName());
    }

    @Test
    void rejectsIncompleteZipIndexWithoutReadingMinio() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "STORED");
        data.setZipDataOffset(null);
        fixture.stubAuthorizedData(data);

        SampleFileException error = assertThrows(
                SampleFileException.class,
                () -> fixture.service.openDataPreview(data.getId())
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        assertEquals("ZIP entry index is incomplete", error.getMessage());
        verify(fixture.minioService, never())
                .downloadRange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsPreviewLargerThanOneHundredMegabytes() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("POINT_CLOUD", "STORED");
        data.setUncompressedSize(100L * 1024 * 1024 + 1);
        fixture.stubAuthorizedData(data);

        SampleFileException error = assertThrows(
                SampleFileException.class,
                () -> fixture.service.openDataPreview(data.getId())
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatus());
        verify(fixture.minioService, never())
                .downloadRange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsDraftDeletedAndUnauthorizedVersionsWithSameGenericError() {
        Fixture draft = new Fixture();
        DatasetSampleData draftData = draft.data("IMAGE", "STORED");
        draft.version.setStatus("DRAFT");
        draft.stubVersionAndData(draftData);
        when(draft.assetRepo.findByIdAndDeletedFalse(draft.asset.getId()))
                .thenReturn(Optional.of(draft.asset));
        when(draft.authContext.canAccessOwner(draft.asset.getOwnerUserId())).thenReturn(true);

        Fixture deleted = new Fixture();
        DatasetSampleData deletedData = deleted.data("IMAGE", "STORED");
        when(deleted.dataRepo.findById(deletedData.getId())).thenReturn(Optional.of(deletedData));
        when(deleted.versionRepo.findByIdAndDeletedFalse(deleted.version.getId()))
                .thenReturn(Optional.empty());

        Fixture unauthorized = new Fixture();
        DatasetSampleData unauthorizedData = unauthorized.data("IMAGE", "STORED");
        unauthorized.stubVersionAndData(unauthorizedData);
        when(unauthorized.assetRepo.findByIdAndDeletedFalse(unauthorized.asset.getId()))
                .thenReturn(Optional.of(unauthorized.asset));
        when(unauthorized.authContext.canAccessOwner(unauthorized.asset.getOwnerUserId()))
                .thenReturn(false);

        for (SampleFileException error : new SampleFileException[]{
                assertThrows(SampleFileException.class,
                        () -> draft.service.openDataDownload(draftData.getId())),
                assertThrows(SampleFileException.class,
                        () -> deleted.service.openDataDownload(deletedData.getId())),
                assertThrows(SampleFileException.class,
                        () -> unauthorized.service.openDataDownload(unauthorizedData.getId()))
        }) {
            assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
            assertEquals("sample file not found or no permission", error.getMessage());
        }
    }

    @Test
    void checksPermissionBeforeRevealingUnsupportedPreviewType() {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("VIDEO", "STORED");
        fixture.stubVersionAndData(data);
        when(fixture.assetRepo.findByIdAndDeletedFalse(fixture.asset.getId()))
                .thenReturn(Optional.of(fixture.asset));
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId())).thenReturn(false);

        SampleFileException error = assertThrows(
                SampleFileException.class,
                () -> fixture.service.openDataPreview(data.getId())
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("sample file not found or no permission", error.getMessage());
    }

    @Test
    void rejectsUnsupportedCompressionMethod() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSampleData data = fixture.data("IMAGE", "BZIP2");
        fixture.stubAuthorizedData(data);

        SampleFileException error = assertThrows(
                SampleFileException.class,
                () -> fixture.service.openDataDownload(data.getId())
        );

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, error.getStatus());
        verify(fixture.minioService, never())
                .downloadRange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    private static byte[] rawDeflate(byte[] source) throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(output, deflater)) {
            deflaterOutput.write(source);
            deflaterOutput.finish();
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static final class Fixture {
        private final DatasetSampleDataRepository dataRepo = mock(DatasetSampleDataRepository.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final MinioService minioService = mock(MinioService.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final DatasetPackage datasetPackage = datasetPackage();
        private final SampleFileService service = new SampleFileService(
                dataRepo,
                sampleRepo,
                annotationRepo,
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                minioService,
                authContext
        );

        private void stubAuthorizedData(DatasetSampleData data) {
            stubVersionAndData(data);
            when(assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private void stubVersionAndData(DatasetSampleData data) {
            when(dataRepo.findById(data.getId())).thenReturn(Optional.of(data));
            DatasetSample sample = new DatasetSample();
            sample.setId(data.getSampleId());
            sample.setDatasetVersionId(version.getId());
            sample.setDeleted(false);
            when(sampleRepo.findByIdAndDeletedFalse(sample.getId())).thenReturn(Optional.of(sample));
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        }

        private void stubAuthorizedAnnotation(DatasetAnnotation annotation) {
            when(annotationRepo.findById(annotation.getId())).thenReturn(Optional.of(annotation));
            DatasetSample sample = new DatasetSample();
            sample.setId(annotation.getSampleId());
            sample.setDatasetVersionId(version.getId());
            sample.setDeleted(false);
            when(sampleRepo.findByIdAndDeletedFalse(sample.getId()))
                    .thenReturn(Optional.of(sample));
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private void stubPackageSource() {
            when(packageRepo.findByIdAndDeletedFalse(datasetPackage.getId()))
                    .thenReturn(Optional.of(datasetPackage));
            when(versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                    version.getId(),
                    datasetPackage.getId()
            )).thenReturn(true);
        }

        private DatasetSampleData data(String dataType, String compressionMethod) {
            DatasetSampleData data = new DatasetSampleData();
            data.setId("data-1");
            data.setSampleId("sample-1");
            data.setDatasetVersionId(version.getId());
            data.setDataType(dataType);
            data.setFileName("image.png");
            data.setContentType("IMAGE".equals(dataType) ? "image/png" : "video/mp4");
            data.setZipDataOffset(123L);
            data.setCompressedSize(3L);
            data.setUncompressedSize(3L);
            data.setCompressionMethod(compressionMethod);
            return data;
        }

        private DatasetAnnotation annotation(String compressionMethod) {
            DatasetAnnotation annotation = new DatasetAnnotation();
            annotation.setId("annotation-1");
            annotation.setSampleId("sample-1");
            annotation.setDatasetVersionId(version.getId());
            annotation.setFileName("annotation.json");
            annotation.setContentType("application/json");
            annotation.setZipDataOffset(456L);
            annotation.setCompressedSize(3L);
            annotation.setUncompressedSize(3L);
            annotation.setCompressionMethod(compressionMethod);
            return annotation;
        }

        private static DatasetVersion version() {
            DatasetVersion version = new DatasetVersion();
            version.setId("version-1");
            version.setAssetId("asset-1");
            version.setStatus("READY");
            version.setStoragePath("users/7/datasets/asset-1/v1/data.zip");
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

        private static DatasetPackage datasetPackage() {
            DatasetPackage datasetPackage = new DatasetPackage();
            datasetPackage.setId("dataset-pkg-1");
            datasetPackage.setDatasetAssetId("asset-1");
            datasetPackage.setStoragePath("users/7/datasets/asset-1/v1/package-primary.zip");
            datasetPackage.setFileName("package-primary.zip");
            datasetPackage.setSizeBytes(1024L);
            datasetPackage.setStatus("READY");
            datasetPackage.setDeleted(false);
            return datasetPackage;
        }
    }

    private record RangeCase(String header, long start, long end) {
    }
}
