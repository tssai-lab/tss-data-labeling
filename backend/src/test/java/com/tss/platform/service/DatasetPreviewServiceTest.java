package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.DatasetContentPreviewDto;
import com.tss.platform.dto.DatasetPreviewFileDto;
import com.tss.platform.dto.DatasetPreviewFileListDto;
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
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetPreviewServiceTest {

    @Test
    void cvZipListReturnsImagesAndAnnotations() throws Exception {
        byte[] zip = zip(
                entry("images/a.png", bytes("image")),
                entry("labels/a.txt", bytes("label")),
                entry("annotations/table.csv", bytes("file,label\na.png,ok\n"))
        );
        TestFixture fixture = fixture("dataset.zip", "CV", 7, (long) zip.length, zip);

        DatasetPreviewFileListDto result = fixture.service.listFiles("dataset-ver-1", 1, 10, null, null);

        assertEquals("dataset-ver-1", result.getDatasetVersionId());
        assertEquals("CV", result.getType());
        assertTrue(result.getSourceArchive());
        assertEquals(3, result.getTotal());
        assertEquals("annotations/table.csv", result.getFiles().get(0).getPath());
        assertEquals("TABLE", result.getFiles().get(0).getKind());
        assertEquals("IMAGE", result.getFiles().get(1).getKind());
        assertEquals("TEXT", result.getFiles().get(2).getKind());
    }

    @Test
    void cvZipImageCanBeRead() throws Exception {
        byte[] image = bytes("png-bytes");
        byte[] zip = zip(entry("images/a.png", image));
        TestFixture fixture = fixture("dataset.zip", "CV", 7, (long) zip.length, zip);

        DatasetPreviewService.DatasetImageStream stream = fixture.service.openImage("dataset-ver-1", "images/a.png");

        try (InputStream is = stream.inputStream()) {
            assertArrayEquals(image, is.readAllBytes());
        }
        assertEquals("a.png", stream.fileName());
        assertEquals("image/png", stream.contentType());
    }

    @Test
    void cvZipTextAnnotationCanBePreviewed() throws Exception {
        byte[] zip = zip(entry("labels/a.txt", bytes("0 0.5 0.5 1 1")));
        TestFixture fixture = fixture("dataset.zip", "CV", 7, (long) zip.length, zip);

        DatasetContentPreviewDto result =
                fixture.service.previewContent("dataset-ver-1", "labels/a.txt", null, null);

        assertEquals("a.txt", result.getFileName());
        assertEquals("TEXT", result.getContentType());
        assertEquals("0 0.5 0.5 1 1", result.getContent());
        assertFalse(result.getTruncated());
    }

    @Test
    void markdownFileIsListedAndCanBePreviewed() throws Exception {
        byte[] zip = zip(entry("docs/README.md", bytes("# Dataset\n\nLeRobot notes.")));
        TestFixture fixture = fixture("dataset.zip", "LEROBOT", 7, (long) zip.length, zip);

        DatasetPreviewFileDto file = fixture.service
                .listFiles("dataset-ver-1", 1, 10, null, null)
                .getFiles()
                .get(0);
        assertEquals("TEXT", file.getKind());
        assertTrue(file.getPreviewAllowed());

        DatasetContentPreviewDto result = fixture.service.previewContent(
                "dataset-ver-1", "docs/README.md", null, null
        );
        assertEquals("MARKDOWN", result.getContentType());
        assertEquals("# Dataset\n\nLeRobot notes.", result.getContent());
        assertFalse(result.getPageable());
    }

    @Test
    void nlpDirectTxtCanBePreviewedWithoutPath() {
        byte[] content = bytes("hello dataset");
        TestFixture fixture = fixture("data.txt", "NLP", 7, (long) content.length, content);

        DatasetContentPreviewDto result = fixture.service.previewContent("dataset-ver-1", null, null, null);

        assertNull(result.getPath());
        assertEquals("data.txt", result.getFileName());
        assertEquals("hello dataset", result.getContent());
        assertTrue(result.getPageable());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getTotalPages());
    }

    @Test
    void nlpDirectTxtReturnsPagedLines() {
        byte[] content = bytes("line1\nline2\nline3\n");
        TestFixture fixture = fixture("data.txt", "NLP", 7, (long) content.length, content);

        DatasetContentPreviewDto result = fixture.service.previewContent("dataset-ver-1", null, 2, 1);

        assertEquals("line2", result.getContent());
        assertTrue(result.getPageable());
        assertEquals(3, result.getTotal());
        assertEquals(3, result.getTotalPages());
        assertEquals(2, result.getPage());
        assertEquals(1, result.getPageSize());
    }

    @Test
    void nlpDirectJsonIsPrettyPrinted() {
        byte[] content = bytes("{\"a\":1,\"b\":true}");
        TestFixture fixture = fixture("data.json", "NLP", 7, (long) content.length, content);

        DatasetContentPreviewDto result = fixture.service.previewContent("dataset-ver-1", null, null, null);

        assertEquals("JSON", result.getContentType());
        assertTrue(result.getContent().contains("\"a\" : 1"));
        assertTrue(result.getContent().contains("\"b\" : true"));
        assertFalse(result.getPageable());
        assertNull(result.getTotal());
        assertNull(result.getTotalPages());
    }

    @Test
    void nlpDirectJsonlReturnsPagedLines() {
        byte[] content = bytes("{\"id\":1}\n{\"id\":2}\n\n{\"id\":3}\n");
        TestFixture fixture = fixture("data.jsonl", "NLP", 7, (long) content.length, content);

        DatasetContentPreviewDto result = fixture.service.previewContent("dataset-ver-1", null, 2, 1);

        assertEquals("JSONL", result.getContentType());
        assertEquals("{\"id\":2}", result.getContent());
        assertTrue(result.getPageable());
        assertEquals(3, result.getTotal());
        assertEquals(3, result.getTotalPages());
        assertEquals(2, result.getPage());
        assertEquals(1, result.getPageSize());
        assertNull(result.getRows());
    }

    @Test
    void nlpZipCsvReturnsColumnsAndPagedRows() throws Exception {
        byte[] zip = zip(entry("tables/data.csv", bytes("name,value\na,1\nb,2\nc,3\n")));
        TestFixture fixture = fixture("dataset.zip", "NLP", 7, (long) zip.length, zip);

        DatasetContentPreviewDto result =
                fixture.service.previewContent("dataset-ver-1", "tables/data.csv", 2, 1);

        assertEquals("CSV", result.getContentType());
        assertEquals(List.of("name", "value"), result.getColumns());
        assertEquals(List.of(List.of("b", "2")), result.getRows());
        assertEquals(2, result.getPage());
        assertEquals(1, result.getPageSize());
        assertTrue(result.getPageable());
        assertEquals(3, result.getTotal());
        assertEquals(3, result.getTotalPages());
    }

    @Test
    void leRobotZipKeepsCommonFilePreview() throws Exception {
        byte[] zip = zip(
                entry("lerobot/meta/info.json", bytes("{\"fps\":30}")),
                entry("lerobot/data/chunk-000/file-000.parquet", bytes("PAR1")),
                entry("lerobot/videos/camera/chunk-000/file-000.mp4", bytes("video"))
        );
        TestFixture fixture = fixture("dataset.zip", "LEROBOT", 7, (long) zip.length, zip);

        DatasetPreviewFileListDto files = fixture.service.listFiles(
                "dataset-ver-1", 1, 20, null, null
        );
        assertEquals(3, files.getTotal());
        assertEquals("LEROBOT", files.getType());
        assertTrue(files.getFiles().stream()
                .filter(file -> file.getPath().endsWith("meta/info.json"))
                .findFirst()
                .orElseThrow()
                .getPreviewAllowed());
        assertFalse(files.getFiles().stream()
                .filter(file -> file.getPath().endsWith(".parquet"))
                .findFirst()
                .orElseThrow()
                .getPreviewAllowed());

        DatasetContentPreviewDto info = fixture.service.previewContent(
                "dataset-ver-1", "lerobot/meta/info.json", null, null
        );
        assertEquals("JSON", info.getContentType());
        assertTrue(info.getContent().contains("\"fps\" : 30"));
    }

    @Test
    void unsupportedDocumentFormatsAreListedButNotPreviewable() throws Exception {
        byte[] zip = zip(
                entry("docs/a.pdf", bytes("pdf")),
                entry("docs/a.docx", bytes("docx")),
                entry("docs/a.xls", bytes("xls")),
                entry("docs/a.xlsx", bytes("xlsx"))
        );
        TestFixture fixture = fixture("dataset.zip", "NLP", 7, (long) zip.length, zip);

        DatasetPreviewFileListDto result = fixture.service.listFiles("dataset-ver-1", 1, 20, null, null);

        assertEquals(4, result.getTotal());
        for (DatasetPreviewFileDto file : result.getFiles()) {
            assertEquals("UNSUPPORTED", file.getKind());
            assertFalse(file.getPreviewAllowed());
            assertNull(file.getPreviewUrl());
            assertNotNull(file.getMessage());
        }
    }

    @Test
    void rejectsOtherUsersDatasetForNormalUser() {
        byte[] content = bytes("hello");
        TestFixture fixture = fixture("data.txt", "NLP", 8, (long) content.length, content);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.listFiles("dataset-ver-1", null, null, null, null)
        );

        assertTrue(error.getMessage().contains("no permission"));
    }

    @Test
    void adminAccessIsAllowedThroughAuthContext() {
        byte[] content = bytes("hello");
        TestFixture fixture = fixture("data.txt", "NLP", 8, (long) content.length, content, true);

        DatasetPreviewFileListDto result = fixture.service.listFiles("dataset-ver-1", null, null, null, null);

        assertEquals(1, result.getTotal());
        assertEquals("data.txt", result.getFiles().get(0).getFileName());
    }

    @Test
    void rejectsNonCommonDatasetType() {
        byte[] content = bytes("pcd");
        TestFixture fixture = fixture("scan.pcd", "POINT_CLOUD", 7, (long) content.length, content);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.listFiles("dataset-ver-1", null, null, null, null)
        );

        assertTrue(error.getMessage().contains("dedicated preview"));
    }

    @Test
    void rejectsDraftOrArchivedDatasetPreview() {
        byte[] content = bytes("hello");
        TestFixture draft = fixture("data.txt", "NLP", 7, (long) content.length, content);
        draft.version().setStatus("DRAFT");
        TestFixture archived = fixture("data.txt", "NLP", 7, (long) content.length, content);
        archived.version().setStatus("ARCHIVED");

        assertThrows(IllegalArgumentException.class, () ->
                draft.service.listFiles("dataset-ver-1", null, null, null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                archived.service.listFiles("dataset-ver-1", null, null, null, null)
        );
    }

    @Test
    void rejectsUnsafeZipEntryPathsInArchive() throws Exception {
        assertUnsafeZipEntryRejected("../a.txt");
        assertUnsafeZipEntryRejected("/a.txt");
        assertUnsafeZipEntryRejected("C:/a.txt");
        assertUnsafeZipEntryRejected("bad\u0000name.txt");
    }

    @Test
    void rejectsUnsafeRequestedZipEntryPaths() throws Exception {
        byte[] zip = zip(entry("safe/a.txt", bytes("safe")));
        TestFixture fixture = fixture("dataset.zip", "NLP", 7, (long) zip.length, zip);

        assertThrows(IllegalArgumentException.class, () ->
                fixture.service.previewContent("dataset-ver-1", "../a.txt", null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                fixture.service.previewContent("dataset-ver-1", "/a.txt", null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                fixture.service.previewContent("dataset-ver-1", "C:/a.txt", null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                fixture.service.previewContent("dataset-ver-1", "bad\u0000name.txt", null, null)
        );
    }

    @Test
    void textPreviewIsTruncatedAtLimit() {
        byte[] content = bytes("abcdefg");
        TestFixture fixture = fixture("data.txt", "NLP", 7, (long) content.length, content, false, 5, 100, 10_000);

        DatasetContentPreviewDto result = fixture.service.previewContent("dataset-ver-1", null, null, null);

        assertEquals("abcde", result.getContent());
        assertTrue(result.getTruncated());
        assertNotNull(result.getMessage());
    }

    @Test
    void oversizedImagePreviewIsRejected() throws Exception {
        byte[] zip = zip(entry("images/a.png", bytes("0123456789")));
        TestFixture fixture = fixture("dataset.zip", "CV", 7, (long) zip.length, zip, false, 1024, 5, 10_000);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fixture.service.openImage("dataset-ver-1", "images/a.png")
        );

        assertTrue(error.getMessage().contains("too large"));
    }

    private void assertUnsafeZipEntryRejected(String path) throws Exception {
        byte[] zip = zip(entry(path, bytes("bad")));
        TestFixture fixture = fixture("dataset.zip", "NLP", 7, (long) zip.length, zip);

        assertThrows(IllegalArgumentException.class, () ->
                fixture.service.listFiles("dataset-ver-1", null, null, null, null)
        );
    }

    private TestFixture fixture(
            String fileName,
            String type,
            Integer ownerUserId,
            Long sizeBytes,
            byte[] minioObject
    ) {
        return fixture(fileName, type, ownerUserId, sizeBytes, minioObject, false);
    }

    private TestFixture fixture(
            String fileName,
            String type,
            Integer ownerUserId,
            Long sizeBytes,
            byte[] minioObject,
            boolean admin
    ) {
        return fixture(fileName, type, ownerUserId, sizeBytes, minioObject, admin, 1024 * 1024, 20L * 1024L * 1024L, 10_000);
    }

    private TestFixture fixture(
            String fileName,
            String type,
            Integer ownerUserId,
            Long sizeBytes,
            byte[] minioObject,
            boolean admin,
            int maxTextBytes,
            long maxImageBytes,
            int maxZipEntries
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
        when(authContext.isAdmin()).thenReturn(admin);
        doAnswer(invocation -> {
            Integer owner = invocation.getArgument(0);
            String message = invocation.getArgument(1);
            if (!admin && !Integer.valueOf(7).equals(owner)) {
                throw new IllegalArgumentException(message);
            }
            return null;
        }).when(authContext).requireOwnerAccess(any(), anyString());
        doAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            Integer owner = invocation.getArgument(1);
            String message = invocation.getArgument(2);
            if (!admin && (!Integer.valueOf(7).equals(owner) || !objectName.startsWith("users/7/"))) {
                throw new IllegalArgumentException(message);
            }
            return null;
        }).when(authContext).requireObjectAccess(anyString(), any(), anyString());

        try {
            when(minioService.downloadStream(version.getStoragePath()))
                    .thenAnswer(invocation -> new ByteArrayInputStream(minioObject));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        DatasetPreviewService service = new DatasetPreviewService(
                versionRepo,
                assetRepo,
                minioService,
                authContext,
                new ObjectMapper(),
                maxZipEntries,
                maxTextBytes,
                maxImageBytes,
                200
        );
        return new TestFixture(service, version);
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Entry entry(String name, byte[] content) {
        return new Entry(name, content);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Entry(String name, byte[] content) {
    }

    private record TestFixture(DatasetPreviewService service, DatasetVersion version) {
    }
}
