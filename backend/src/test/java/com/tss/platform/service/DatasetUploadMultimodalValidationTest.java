package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetUploadMultimodalValidationTest {

    @Test
    void defaultsManifestPathForManifestGrouping() {
        assertEquals(
                "manifest.json",
                DatasetUploadService.normalizeManifestPath("MANIFEST", null)
        );
    }

    @Test
    void rejectsUnsafeManifestPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeManifestPath("MANIFEST", "/manifest.json"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeManifestPath("MANIFEST", "../manifest.json"));
        assertEquals(
                "dir/manifest.json",
                DatasetUploadService.normalizeManifestPath("MANIFEST", "dir\\manifest.json")
        );
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeManifestPath("MANIFEST", "bad\u0000manifest.json"));
    }

    @Test
    void rejectsManifestPathWithoutManifestGrouping() {
        assertNull(DatasetUploadService.normalizeManifestPath(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeManifestPath(null, "manifest.json"));
    }

    @Test
    void acceptsManifestAndAutoDirectoryGrouping() {
        assertNull(DatasetUploadService.normalizeSampleGrouping(null));
        assertEquals("MANIFEST", DatasetUploadService.normalizeSampleGrouping(" manifest "));
        assertEquals(
                "AUTO_DIRECTORY",
                DatasetUploadService.normalizeSampleGrouping(" auto_directory ")
        );
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeSampleGrouping("BASENAME"));
    }

    @Test
    void defaultsMultimodalGroupingToAutoDirectory() {
        assertEquals(
                "AUTO_DIRECTORY",
                DatasetUploadService.normalizeSampleGroupingForTask("MULTIMODAL", null)
        );
        assertEquals(
                "MANIFEST",
                DatasetUploadService.normalizeSampleGroupingForTask("MULTIMODAL", "manifest")
        );
        assertNull(DatasetUploadService.normalizeSampleGroupingForTask("CV", null));
    }

    @Test
    void autoDirectoryRejectsManifestPath() {
        assertNull(DatasetUploadService.normalizeManifestPath("AUTO_DIRECTORY", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeManifestPath(
                        "AUTO_DIRECTORY",
                        "manifest.json"
                )
        );
    }

    @Test
    void strictManifestDefaultsFalseAndRequiresManifestGrouping() {
        assertEquals(
                false,
                DatasetUploadService.normalizeStrictManifestForTask("MULTIMODAL", "MANIFEST", null)
        );
        assertEquals(
                true,
                DatasetUploadService.normalizeStrictManifestForTask("MULTIMODAL", "MANIFEST", true)
        );
        assertEquals(
                false,
                DatasetUploadService.normalizeStrictManifestForTask("MULTIMODAL", "AUTO_DIRECTORY", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeStrictManifestForTask(
                        "MULTIMODAL",
                        "AUTO_DIRECTORY",
                        true
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.normalizeStrictManifestForTask("CV", null, true)
        );
    }

    @Test
    void calculatesDynamicChunkSizeWithAtMostTenThousandParts() {
        long fileSize = 50L * 1024 * 1024 * 1024;

        int chunkSize = DatasetUploadService.calculateChunkSize(fileSize);
        int totalChunks = DatasetUploadService.calculateTotalChunks(fileSize, chunkSize);

        assertEquals(6 * 1024 * 1024, chunkSize);
        assertEquals(8534, totalChunks);
    }

    @Test
    void keepsFiveMegabyteChunksForSmallFiles() {
        long fileSize = 11L * 1024 * 1024;

        int chunkSize = DatasetUploadService.calculateChunkSize(fileSize);

        assertEquals(5 * 1024 * 1024, chunkSize);
        assertEquals(3, DatasetUploadService.calculateTotalChunks(fileSize, chunkSize));
    }

    @Test
    void acceptsMultimodalZipFileName() {
        DatasetUploadService.validateDatasetFileNameForTask("MULTIMODAL", "dataset.zip");
        assertThrows(IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetFileNameForTask("MULTIMODAL", "dataset.tar"));
    }
}
