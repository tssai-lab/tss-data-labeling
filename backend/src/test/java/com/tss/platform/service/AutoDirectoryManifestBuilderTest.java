package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoDirectoryManifestBuilderTest {

    private final AutoDirectoryManifestBuilder builder =
            new AutoDirectoryManifestBuilder();

    @Test
    void groupsFilesByTopLevelDirectoryAndInfersTypes() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene_002/cloud/lidar.ply"),
                entry("scene_001/image/front.jpg"),
                entry("scene_001/cloud/lidar.pcd")
        ), 0);

        assertEquals(
                List.of("scene_001", "scene_002"),
                plan.samples().stream().map(ManifestSample::externalId).toList()
        );
        assertEquals(
                List.of("POINT_CLOUD", "IMAGE"),
                plan.samples().get(0).data().stream()
                        .map(ManifestData::dataType)
                        .toList()
        );
        assertEquals(2, plan.totalSamples());
        assertEquals(3, plan.totalDataCount());
        assertEquals(0, plan.totalAnnotationCount());
    }

    @Test
    void permitsImageAndPointCloudOnly() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene/image.jpg"),
                entry("scene/cloud.pcd")
        ), 0);

        assertEquals(1, plan.totalSamples());
        assertEquals(2, plan.totalDataCount());
        assertEquals(0, plan.totalAnnotationCount());
    }

    @Test
    void generatesStableIndexesAndSequences() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene-b/image/z.png"),
                entry("scene-a/image/z.jpg"),
                entry("scene-a/image/a.png"),
                entry("scene-a/cloud/a.pcd")
        ), 42);

        assertEquals(42, plan.samples().get(0).sampleIndex());
        assertEquals(43, plan.samples().get(1).sampleIndex());
        assertEquals(
                List.of(0, 0, 1),
                plan.samples().get(0).data().stream().map(ManifestData::seq).toList()
        );
        assertNull(plan.samples().get(0).data().get(0).sensor());
        assertNull(plan.samples().get(0).data().get(0).channel());
    }

    @Test
    void infersSupportedDataTypesAndContentTypes() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene/image/a.webp"),
                entry("scene/cloud/a.ply"),
                entry("scene/video/a.webm"),
                entry("scene/audio/a.mp3"),
                entry("scene/text/a.md"),
                entry("scene/other/a.npy"),
                entry("scene/other/a.log")
        ), 0);

        assertEquals(
                List.of(
                        "AUDIO:audio/mpeg",
                        "POINT_CLOUD:application/vnd.ply",
                        "IMAGE:image/webp",
                        "OTHER:text/plain",
                        "OTHER:application/octet-stream",
                        "TEXT:text/markdown",
                        "VIDEO:video/webm"
                ),
                plan.samples().get(0).data().stream()
                        .map(item -> item.dataType() + ":" + item.contentType())
                        .toList()
        );
    }

    @Test
    void associatesAnnotationByExactDataFileName() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene/image/front.jpg"),
                entry("scene/annotations/front.jpg.json")
        ), 0);

        assertEquals(
                "scene/image/front.jpg",
                plan.samples().get(0).annotations().get(0).refDataPath()
        );
        assertEquals("OTHER", plan.samples().get(0).annotations().get(0).annotationType());
        assertEquals("JSON", plan.samples().get(0).annotations().get(0).format());
    }

    @Test
    void associatesAnnotationByUniqueDataStem() {
        ManifestImportPlan plan = builder.build(List.of(
                entry("scene/image/front.jpg"),
                entry("scene/annotations/front.json")
        ), 0);

        assertEquals(
                "scene/image/front.jpg",
                plan.samples().get(0).annotations().get(0).refDataPath()
        );
    }

    @Test
    void rejectsAmbiguousAnnotationStem() {
        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(
                        entry("scene/image/front.jpg"),
                        entry("scene/cloud/front.pcd"),
                        entry("scene/annotations/front.json")
                ), 0)
        );

        assertTrue(error.getMessage().contains("ambiguous"));
        assertTrue(error.getMessage().contains("external_id: scene"));
        assertTrue(error.getMessage().contains("scene/annotations/front.json"));
    }

    @Test
    void rejectsUnmatchedAnnotation() {
        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(
                        entry("scene/image/front.jpg"),
                        entry("scene/annotations/rear.json")
                ), 0)
        );

        assertTrue(error.getMessage().contains("not found"));
        assertTrue(error.getMessage().contains("external_id: scene"));
    }

    @Test
    void rejectsUnknownExtensionAndRootLevelFile() {
        assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(entry("scene/data/file.xyz")), 0)
        );
        ManifestValidationException rootError = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(entry("front.jpg")), 0)
        );
        assertTrue(rootError.getMessage().contains("sample directory"));
    }

    @Test
    void rejectsAnnotationOnlySample() {
        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(
                        entry("scene/annotations/front.json")
                ), 0)
        );

        assertTrue(error.getMessage().contains("at least one data file"));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(null, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(List.of(entry("scene/image/front.jpg")), -1)
        );
        List<ZipEntryInfo> entries = new ArrayList<>();
        entries.add(null);
        assertThrows(IllegalArgumentException.class, () -> builder.build(entries, 0));
    }

    @Test
    void ignoresDirectoryEntries() {
        ManifestImportPlan plan = builder.build(List.of(
                directory("scene/"),
                directory("scene/image/"),
                entry("scene/image/front.jpg")
        ), 0);

        assertEquals(1, plan.totalSamples());
        assertEquals(1, plan.totalDataCount());
    }

    @Test
    void rejectsExternalIdLongerThanDatabaseLimit() {
        String externalId = "x".repeat(256);

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(List.of(
                        entry(externalId + "/image/front.jpg")
                ), 0)
        );

        assertTrue(error.getMessage().contains("external_id"));
    }

    @Test
    void rejectsMoreThanOneHundredDataFilesPerSample() {
        List<ZipEntryInfo> entries = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            entries.add(entry("scene/text/file-" + index + ".txt"));
        }

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(entries, 0)
        );

        assertTrue(error.getMessage().contains("data count exceeds 100"));
    }

    @Test
    void rejectsMoreThanTenThousandSamples() {
        List<ZipEntryInfo> entries = new ArrayList<>();
        for (int index = 0; index < 10_001; index++) {
            entries.add(entry("scene-" + index + "/text/file.txt"));
        }

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> builder.build(entries, 0)
        );

        assertTrue(error.getMessage().contains("samples count exceeds 10000"));
    }

    private static ZipEntryInfo entry(String path) {
        return zipEntry(path, false);
    }

    private static ZipEntryInfo directory(String path) {
        return zipEntry(path, true);
    }

    private static ZipEntryInfo zipEntry(String path, boolean directory) {
        return new ZipEntryInfo(
                path,
                path,
                0,
                10,
                10,
                1,
                0,
                30,
                false,
                directory
        );
    }
}
