package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetUploadServiceLeRobotTest {

    @Test
    void leRobotOnlyAcceptsZip() {
        DatasetUploadService.validateDatasetFileNameForTask("LEROBOT", "dataset.zip");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetFileNameForTask("LEROBOT", "data.parquet")
        );
        assertTrue(error.getMessage().contains("LEROBOT"));
    }

    @Test
    void leRobotRequiresV3Layout() throws Exception {
        byte[] archive = zip(
                entry("meta/info.json", "{\"codebase_version\":\"v3.0\",\"fps\":30,\"features\":{}}"),
                entry("meta/episodes/chunk-000/file-000.parquet", "PAR1"),
                entry("data/chunk-000/file-000.parquet", "PAR1"),
                entry("videos/observation.images.top/chunk-000/file-000.mp4", "video")
        );
        assertDoesNotThrow(() -> DatasetUploadService.validateDatasetZipEntries(
                "LEROBOT", null, new ByteArrayInputStream(archive)
        ));
    }

    @Test
    void leRobotAcceptsRepositoryRootDirectoryAndGitAttributes() throws Exception {
        String root = "lerobot-svla_so100_stacking/";
        byte[] archive = zip(
                entry(root + ".gitattributes", "*.parquet filter=lfs diff=lfs merge=lfs -text"),
                entry(root + "README.md", "# LeRobot dataset"),
                entry(root + "meta/info.json", "{\"codebase_version\":\"v3.0\",\"fps\":30,\"features\":{}}"),
                entry(root + "meta/episodes/chunk-000/file-000.parquet", "PAR1"),
                entry(root + "data/chunk-000/file-000.parquet", "PAR1"),
                entry(root + "videos/observation.images.top/chunk-000/file-000.mp4", "video")
        );
        assertDoesNotThrow(() -> DatasetUploadService.validateDatasetZipEntries(
                "LEROBOT", null, new ByteArrayInputStream(archive)
        ));
    }

    @Test
    void leRobotAcceptsV21EpisodeLayoutAndMkvVideo() throws Exception {
        String root = "lerobot-singleTask/";
        byte[] archive = zip(
                entry(root + "meta/info.json", "{\"codebase_version\":\"v2.1\",\"fps\":30,\"features\":{}}"),
                entry(root + "meta/episodes.jsonl", "{\"episode_index\":0,\"length\":10,\"tasks\":[\"pick\"]}"),
                entry(root + "data/chunk-000/episode_000000.parquet", "PAR1"),
                entry(root + "videos/chunk-000/observation.images.agentview/episode_000000.mp4", "video"),
                entry(root + "videos/chunk-000/observation.images.agentview_depth/episode_000000.mkv", "depth")
        );
        assertDoesNotThrow(() -> DatasetUploadService.validateDatasetZipEntries(
                "LEROBOT", null, new ByteArrayInputStream(archive)
        ));
    }

    @Test
    void leRobotStillRejectsExecutableSupportFiles() throws Exception {
        String root = "lerobot-svla_so100_stacking/";
        byte[] archive = zip(
                entry(root + "viewer/server.py", "print('viewer')"),
                entry(root + "meta/info.json", "{}"),
                entry(root + "meta/episodes/chunk-000/file-000.parquet", "PAR1"),
                entry(root + "data/chunk-000/file-000.parquet", "PAR1"),
                entry(root + "videos/camera/chunk-000/file-000.mp4", "video")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "LEROBOT", null, new ByteArrayInputStream(archive)
                )
        );
        assertTrue(error.getMessage().contains("viewer/server.py"));
    }

    @Test
    void leRobotRejectsMissingVideo() throws Exception {
        byte[] archive = zip(
                entry("meta/info.json", "{}"),
                entry("meta/episodes/chunk-000/file-000.parquet", "PAR1"),
                entry("data/chunk-000/file-000.parquet", "PAR1")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "LEROBOT", null, new ByteArrayInputStream(archive)
                )
        );
        assertTrue(error.getMessage().contains("videos"));
    }

    @Test
    void robotRulesRemainRestricted() throws Exception {
        byte[] archive = zip(entry("data/file.parquet", "PAR1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "ROBOT", null, new ByteArrayInputStream(archive)
                )
        );
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private record Entry(String name, String content) { }
}
