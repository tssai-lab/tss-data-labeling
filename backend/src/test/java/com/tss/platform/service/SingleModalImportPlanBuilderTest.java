package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleModalImportPlanBuilderTest {

    private final SingleModalImportPlanBuilder builder =
            new SingleModalImportPlanBuilder();

    @Test
    void buildsOneSamplePerCvZipFile() {
        ManifestImportPlan plan = builder.build(
                "CV",
                List.of(
                        directory("images/"),
                        entry("images/front.jpg"),
                        entry("labels/front.json")
                ),
                5
        );

        assertEquals(2, plan.totalSamples());
        assertEquals(2, plan.totalDataCount());
        assertEquals(0, plan.totalAnnotationCount());
        assertEquals(
                List.of("images/front.jpg", "labels/front.json"),
                plan.samples().stream().map(ManifestSample::externalId).toList()
        );
        assertEquals(List.of(5, 6), plan.samples().stream()
                .map(ManifestSample::sampleIndex)
                .toList());

        ManifestData image = plan.samples().get(0).data().get(0);
        assertEquals("IMAGE", image.dataType());
        assertEquals("jpg", image.format());
        assertEquals("image/jpeg", image.contentType());
        assertEquals("front.jpg", image.fileName());
    }

    @Test
    void infersSingleModalDataTypes() {
        ManifestImportPlan plan = builder.build(
                "POINT_CLOUD",
                List.of(entry("scan.pcd")),
                0
        );

        ManifestData data = plan.samples().get(0).data().get(0);
        assertEquals("POINT_CLOUD", data.dataType());
        assertEquals("pcd", data.format());
        assertEquals("application/vnd.pointcloud", data.contentType());
    }

    @Test
    void buildsLeRobotRepositoryFileIndex() {
        ManifestImportPlan plan = builder.build(
                "LEROBOT",
                List.of(
                        entry("lerobot/.gitattributes"),
                        entry("lerobot/README.md"),
                        entry("lerobot/meta/info.json"),
                        entry("lerobot/data/chunk-000/file-000.parquet"),
                        entry("lerobot/videos/camera/chunk-000/file-000.mp4")
                ),
                0
        );

        assertEquals(5, plan.totalSamples());
        assertEquals(
                List.of("OTHER", "TEXT", "OTHER", "TEXT", "VIDEO"),
                plan.samples().stream()
                        .map(sample -> sample.data().get(0).dataType())
                        .toList()
        );
        ManifestData parquet = plan.samples().stream()
                .map(sample -> sample.data().get(0))
                .filter(data -> "parquet".equals(data.format()))
                .findFirst()
                .orElseThrow();
        assertEquals("application/vnd.apache.parquet", parquet.contentType());
    }

    @Test
    void indexesLeRobotMkvAsVideo() {
        ManifestImportPlan plan = builder.build(
                "LEROBOT",
                List.of(entry("videos/chunk-000/depth/episode_000000.mkv")),
                0
        );

        ManifestData video = plan.samples().get(0).data().get(0);
        assertEquals("VIDEO", video.dataType());
        assertEquals("mkv", video.format());
        assertEquals("video/x-matroska", video.contentType());
    }

    @Test
    void indexesOtherFilesBySafeContentKindWithoutGrantingATrainingType() {
        ManifestImportPlan plan = builder.build(
                "OTHER",
                List.of(
                        entry("archive/image.png"),
                        entry("archive/scan.ply"),
                        entry("archive/notes.yaml")
                ),
                0
        );

        assertEquals(
                List.of("IMAGE", "TEXT", "POINT_CLOUD"),
                plan.samples().stream()
                        .map(sample -> sample.data().get(0).dataType())
                        .toList()
        );
    }

    @Test
    void rejectsEmptySingleModalZip() {
        assertThrows(
                ManifestValidationException.class,
                () -> builder.build("CV", List.of(directory("images/")), 0)
        );
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
                8,
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
