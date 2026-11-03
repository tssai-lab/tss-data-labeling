package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetUploadServicePointCloudTest {

    @Test
    void acceptsPointCloudPcdFileName() {
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetFileNameForTask("POINT_CLOUD", "scan.pcd")
        );
    }

    @Test
    void acceptsPointCloudPlyFileName() {
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetFileNameForTask("POINT_CLOUD", "scan.ply")
        );
    }

    @Test
    void acceptsPointCloudZipWithPointCloudAndMetadataFiles() {
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetZipEntries(
                        "POINT_CLOUD",
                        null,
                        zip(
                                entry("clouds/sample.pcd", "# .PCD v0.7\nDATA ascii\n"),
                                entry("clouds/sample.ply", "ply\nformat ascii 1.0\nend_header\n"),
                                entry("labels/info.txt", "label"),
                                entry("meta/config.json", "{}"),
                                entry("meta/config.yaml", "name: sample\n")
                        )
                )
        );
    }

    @Test
    void rejectsObjPointCloudFileName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                DatasetUploadService.validateDatasetFileNameForTask("POINT_CLOUD", "mesh.obj")
        );

        assertTrue(error.getMessage().contains("POINT_CLOUD"));
    }

    @Test
    void rejectsPointCloudZipWithoutPlyOrPcdFile() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                DatasetUploadService.validateDatasetZipEntries(
                        "POINT_CLOUD",
                        null,
                        zip(entry("meta/readme.txt", "metadata only"))
                )
        );

        assertTrue(error.getMessage().contains(".ply") || error.getMessage().contains(".pcd"));
    }

    @Test
    void rejectsPointCloudZipWithIllegalPath() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                DatasetUploadService.validateDatasetZipEntries(
                        "POINT_CLOUD",
                        null,
                        zip(entry("../escape.pcd", "bad path"))
                )
        );

        assertTrue(error.getMessage().contains("非法路径") || error.getMessage().contains("path"));
    }

    @Test
    void rejectsPointCloudZipWithDisallowedExtension() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                DatasetUploadService.validateDatasetZipEntries(
                        "POINT_CLOUD",
                        null,
                        zip(entry("mesh.obj", "o mesh"))
                )
        );

        assertTrue(error.getMessage().contains("POINT_CLOUD"));
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static InputStream zip(Entry... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private record Entry(String name, String content) {
    }
}
