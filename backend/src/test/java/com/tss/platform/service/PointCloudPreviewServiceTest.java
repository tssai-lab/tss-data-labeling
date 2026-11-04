package com.tss.platform.service;

import com.tss.platform.dto.PointCloudPreviewDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PointCloudPreviewServiceTest {

    @Test
    void pcdPreviewReturnsSupportedPcd() {
        TestFixture fixture = fixture("scan.pcd", "POINT_CLOUD", 7, 1024L, 100_000, null);

        PointCloudPreviewDto preview = fixture.service.preview("dataset-ver-1");

        assertTrue(preview.isPreviewSupported());
        assertEquals("PCD", preview.getFormat());
        assertEquals("/api/dataset/point-cloud/file?id=dataset-ver-1", preview.getPreviewUrl());
    }

    @Test
    void plyPreviewReturnsSupportedPly() {
        TestFixture fixture = fixture("scan.ply", "POINT_CLOUD", 7, 1024L, 100_000, null);

        PointCloudPreviewDto preview = fixture.service.preview("dataset-ver-1");

        assertTrue(preview.isPreviewSupported());
        assertEquals("PLY", preview.getFormat());
    }

    @Test
    void zipPreviewReturnsPointCloudFiles() throws Exception {
        byte[] zip = zip(
                entry("clouds/scan1.pcd", "pcd"),
                entry("clouds/scan2.ply", "ply"),
                entry("meta/info.json", "{}")
        );
        TestFixture fixture = fixture("pointcloud.zip", "POINT_CLOUD", 7, (long) zip.length, 100_000, zip);

        PointCloudPreviewDto preview = fixture.service.preview("dataset-ver-1");

        assertTrue(preview.isPreviewSupported());
        assertEquals("ZIP", preview.getFormat());
        assertNotNull(preview.getPointCloudFiles());
        assertEquals(2, preview.getPointCloudFiles().size());
        assertEquals("clouds/scan1.pcd", preview.getPointCloudFiles().get(0).getPath());
        assertEquals(3L, preview.getPointCloudFiles().get(0).getSizeBytes());
        assertTrue(preview.getPointCloudFiles().get(0).isPreviewAllowed());
        assertTrue(preview.getPointCloudFiles().get(0).getPreviewUrl().contains("/api/dataset/point-cloud/zip-file"));
    }

    @Test
    void zipFileReturnsSelectedPointCloudEntryStream() throws Exception {
        byte[] content = "pcd-content".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zip(entry("clouds/scan.pcd", new String(content, StandardCharsets.UTF_8)));
        TestFixture fixture = fixture("pointcloud.zip", "POINT_CLOUD", 7, (long) zip.length, 100_000, zip);

        PointCloudPreviewService.PointCloudFileStream stream =
                fixture.service.openZipPointCloudFile("dataset-ver-1", "clouds/scan.pcd");

        try (InputStream is = stream.inputStream()) {
            assertArrayEquals(content, is.readAllBytes());
        }
        assertEquals("PCD", stream.format());
    }

    @Test
    void zipPreviewRejectsEntryWhoseResolvedSizeExceedsLimit() throws Exception {
        byte[] zip = zip(entry("clouds/large.pcd", "x".repeat(101)));
        TestFixture fixture = fixture("pointcloud.zip", "POINT_CLOUD", 7, (long) zip.length, 100, zip);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.preview("dataset-ver-1")
        );

        assertEquals("文件过大，请下载后本地查看", error.getMessage());
    }

    @Test
    void rejectsUnsafeZipEntryPathInPreview() throws Exception {
        byte[] zip = zip(entry("../escape.pcd", "bad"));
        TestFixture fixture = fixture("pointcloud.zip", "POINT_CLOUD", 7, (long) zip.length, 100_000, zip);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.preview("dataset-ver-1")
        );

        assertTrue(error.getMessage().contains("非法"));
    }

    @Test
    void zipPreviewAllowsDoubleDotInsideLegalFileName() throws Exception {
        byte[] zip = zip(entry("clouds/foo..bar.pcd", "pcd"));
        TestFixture fixture = fixture("pointcloud.zip", "POINT_CLOUD", 7, (long) zip.length, 100_000, zip);

        PointCloudPreviewDto preview = fixture.service.preview("dataset-ver-1");

        assertTrue(preview.isPreviewSupported());
        assertEquals("clouds/foo..bar.pcd", preview.getPointCloudFiles().get(0).getPath());
    }

    @Test
    void rejectsNonPointCloudDataset() {
        TestFixture fixture = fixture("image.zip", "CV", 7, 1024L, 100_000, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.preview("dataset-ver-1")
        );

        assertTrue(error.getMessage().contains("POINT_CLOUD"));
    }

    @Test
    void rejectsArchivedPointCloudDatasetPreview() {
        TestFixture fixture = fixture("scan.pcd", "POINT_CLOUD", 7, 1024L, 100_000, null);
        fixture.version.setStatus("ARCHIVED");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.preview("dataset-ver-1")
        );

        assertTrue(error.getMessage().contains("READY"));
    }

    @Test
    void rejectsOtherUsersDatasetForNormalUser() {
        TestFixture fixture = fixture("scan.pcd", "POINT_CLOUD", 8, 1024L, 100_000, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.preview("dataset-ver-1")
        );

        assertTrue(error.getMessage().contains("no permission"));
    }

    @Test
    void oversizedSingleFilePreviewIsNotSupported() {
        TestFixture fixture = fixture("scan.pcd", "POINT_CLOUD", 7, 1024L, 100, null);
        fixture.version.setSizeBytes(101L);

        PointCloudPreviewDto preview = fixture.service.preview("dataset-ver-1");

        assertFalse(preview.isPreviewSupported());
        assertEquals("文件过大，请下载后本地查看", preview.getMessage());
    }

    private TestFixture fixture(
            String fileName,
            String type,
            Integer ownerUserId,
            Long sizeBytes,
            long maxPreviewSize,
            byte[] minioObject
    ) {
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        MinioService minioService = mock(MinioService.class);
        AuthContext authContext = mock(AuthContext.class);

        DatasetVersion version = new DatasetVersion();
        version.setId("dataset-ver-1");
        version.setAssetId("dataset-asset-1");
        version.setFileName(fileName);
        version.setStoragePath("users/" + ownerUserId + "/datasets/dataset-asset-1/v1/" + fileName);
        version.setSizeBytes(sizeBytes);
        version.setOwnerUserId(ownerUserId);

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setType(type);
        asset.setOwnerUserId(ownerUserId);

        when(versionRepo.findByIdAndDeletedFalse("dataset-ver-1")).thenReturn(Optional.of(version));
        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        doAnswer(invocation -> {
            Integer owner = invocation.getArgument(0);
            String message = invocation.getArgument(1);
            if (!Integer.valueOf(7).equals(owner)) {
                throw new IllegalArgumentException(message);
            }
            return null;
        }).when(authContext).requireOwnerAccess(any(), anyString());

        if (minioObject != null) {
            try {
                when(minioService.downloadStream(version.getStoragePath()))
                        .thenAnswer(invocation -> new ByteArrayInputStream(minioObject));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        PointCloudPreviewService service = new PointCloudPreviewService(
                versionRepo,
                assetRepo,
                minioService,
                authContext,
                maxPreviewSize
        );
        return new TestFixture(service, version);
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private record Entry(String name, String content) {
    }

    private record TestFixture(PointCloudPreviewService service, DatasetVersion version) {
    }
}
