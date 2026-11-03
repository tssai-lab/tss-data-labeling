package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestImportPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser(new ObjectMapper());

    @Test
    void parsesValidManifestIntoImportPlan() {
        String json = """
                {
                  "version": "1.0",
                  "samples": [{
                    "external_id": "scene_001",
                    "sample_index": 7,
                    "tags": {"weather": "sunny"},
                    "metadata": {"split": "train"},
                    "data": [{
                      "path": "samples/rgb.png",
                      "data_type": "IMAGE",
                      "sensor": "CAM_FRONT",
                      "channel": "RGB",
                      "seq": 0,
                      "format": "png",
                      "metadata": {"width": 1920}
                    }],
                    "annotations": [{
                      "path": "labels/rgb.json",
                      "annotation_type": "BBOX",
                      "format": "COCO",
                      "ref_data_path": "samples/rgb.png",
                      "metadata": {"reviewed": true}
                    }]
                  }]
                }
                """;

        ManifestImportPlan plan = parser.parse(
                json,
                entries("manifest.json", "samples/rgb.png", "labels/rgb.json"),
                "manifest.json"
        );

        assertEquals("1.0", plan.version());
        assertEquals(1, plan.totalSamples());
        assertEquals(1, plan.totalDataCount());
        assertEquals(1, plan.totalAnnotationCount());
        assertEquals(7, plan.samples().get(0).sampleIndex());
        assertEquals("sunny", plan.samples().get(0).tags().get("weather"));
        assertEquals("image/png", plan.samples().get(0).data().get(0).contentType());
        assertEquals("rgb.png", plan.samples().get(0).data().get(0).fileName());
        assertEquals("samples/rgb.png", plan.samples().get(0).data().get(0).zipEntryInfo().path());
        assertEquals("samples/rgb.png", plan.samples().get(0).annotations().get(0).refDataPath());
        assertEquals("application/json", plan.samples().get(0).annotations().get(0).contentType());
        assertEquals(
                "labels/rgb.json",
                plan.samples().get(0).annotations().get(0).zipEntryInfo().path()
        );
        assertTrue(plan.warnings().isEmpty());
    }

    @Test
    void normalizesBackslashManifestPathsBeforeMatchingZipEntries() {
        String json = """
                {
                  "version": "1.0",
                  "samples": [{
                    "external_id": "scene_001",
                    "data": [{
                      "path": "samples\\\\rgb.png",
                      "data_type": "IMAGE"
                    }],
                    "annotations": [{
                      "path": "labels\\\\rgb.json",
                      "annotation_type": "BBOX",
                      "format": "COCO",
                      "ref_data_path": "samples\\\\rgb.png"
                    }]
                  }]
                }
                """;

        ManifestImportPlan plan = parser.parse(
                json,
                entries("meta/manifest.json", "samples/rgb.png", "labels/rgb.json"),
                "meta\\manifest.json"
        );

        assertEquals("samples/rgb.png", plan.samples().get(0).data().get(0).path());
        assertEquals("labels/rgb.json", plan.samples().get(0).annotations().get(0).path());
        assertEquals("samples/rgb.png", plan.samples().get(0).annotations().get(0).refDataPath());
    }

    @Test
    void generatesMissingSampleIndexFromArrayPosition() {
        String json = manifest(samples(
                sample("first", null, "first.png"),
                sample("second", null, "second.png")
        ));

        ManifestImportPlan plan = parser.parse(
                json,
                entries("manifest.json", "first.png", "second.png"),
                "manifest.json"
        );

        assertEquals(0, plan.samples().get(0).sampleIndex());
        assertEquals(1, plan.samples().get(1).sampleIndex());
    }

    @Test
    void generatesMissingSampleIndexFromAppendStart() {
        String json = manifest(samples(
                sample("first", null, "first.png"),
                sample("second", null, "second.png")
        ));

        ManifestImportPlan plan = parser.parse(
                json,
                entries("manifest.json", "first.png", "second.png"),
                "manifest.json",
                42
        );

        assertEquals(42, plan.samples().get(0).sampleIndex());
        assertEquals(43, plan.samples().get(1).sampleIndex());
    }

    @Test
    void rejectsDuplicateExternalId() {
        String json = manifest(samples(
                sample("scene_001", 0, "first.png"),
                sample("scene_001", 1, "second.png")
        ));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "first.png", "second.png"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("duplicate external_id: scene_001"));
        assertEquals(
                "samples[1].external_id",
                error.getDetails().get("field")
        );
        assertEquals("scene_001", error.getDetails().get("externalId"));
        assertEquals(
                "duplicate external_id: scene_001",
                error.getDetails().get("reason")
        );
    }

    @Test
    void rejectsDuplicateSampleIndex() {
        String json = manifest(samples(
                sample("first", 3, "first.png"),
                sample("second", 3, "second.png")
        ));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "first.png", "second.png"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("duplicate sample_index: 3"));
        assertEquals(
                "samples[1].sample_index",
                error.getDetails().get("field")
        );
        assertEquals(3, error.getDetails().get("sampleIndex"));
    }

    @Test
    void rejectsPathMissingFromZip() {
        String json = manifest(samples(sample("scene_001", 0, "missing.png")));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("path not found in zip: missing.png"));
        assertTrue(error.getMessage().contains("external_id: scene_001"));
        assertEquals(
                "samples[0].data[0].path",
                error.getDetails().get("field")
        );
        assertEquals("missing.png", error.getDetails().get("path"));
        assertEquals("scene_001", error.getDetails().get("externalId"));
    }

    @Test
    void rejectsParentTraversalPath() {
        String json = manifest(samples(sample("scene_001", 0, "../secret.png")));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("data.path"));
        assertTrue(error.getMessage().contains("../secret.png"));
    }

    @Test
    void rejectsGloballyDuplicatedDataPath() {
        String json = manifest(samples(
                sample("first", 0, "shared.png"),
                sample("second", 1, "shared.png")
        ));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "shared.png"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("duplicate manifest path: shared.png"));
    }

    @Test
    void rejectsRefDataPathOutsideCurrentSample() {
        String json = """
                {
                  "version": "1.0",
                  "samples": [
                    {
                      "external_id": "first",
                      "data": [{"path":"first.png","data_type":"IMAGE"}]
                    },
                    {
                      "external_id": "second",
                      "data": [{"path":"second.png","data_type":"IMAGE"}],
                      "annotations": [{
                        "path":"second.json",
                        "annotation_type":"BBOX",
                        "format":"JSON",
                        "ref_data_path":"first.png"
                      }]
                    }
                  ]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(
                        json,
                        entries("manifest.json", "first.png", "second.png", "second.json"),
                        "manifest.json"
                )
        );

        assertTrue(error.getMessage().contains("ref_data_path not found in current sample"));
        assertTrue(error.getMessage().contains("external_id: second"));
    }

    @Test
    void parsesMp4VideoAndInfersContentType() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"video_sample",
                    "data":[{
                      "path":"videos/front.mp4",
                      "data_type":"VIDEO",
                      "format":"mp4",
                      "metadata":{"duration_sec":12.5,"fps":30}
                    }]
                  }]
                }
                """;

        ManifestImportPlan plan = parser.parse(
                json,
                entries("manifest.json", "videos/front.mp4"),
                "manifest.json"
        );

        assertEquals("video/mp4", plan.samples().get(0).data().get(0).contentType());
        assertEquals(12.5, plan.samples().get(0).data().get(0).metadata().get("duration_sec"));
    }

    @Test
    void rejectsFlvVideo() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"video_sample",
                    "data":[{
                      "path":"videos/front.flv",
                      "data_type":"VIDEO",
                      "format":"flv"
                    }]
                  }]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "videos/front.flv"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("invalid video format: flv"));
    }

    @Test
    void rejectsMoreThanOneHundredDataItemsInSample() {
        StringBuilder data = new StringBuilder();
        List<String> paths = new ArrayList<>();
        paths.add("manifest.json");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                data.append(',');
            }
            String path = "data/" + i + ".bin";
            paths.add(path);
            data.append("{\"path\":\"").append(path).append("\",\"data_type\":\"OTHER\"}");
        }
        String json = """
                {"version":"1.0","samples":[{"external_id":"too_many","data":[%s]}]}
                """.formatted(data);

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries(paths.toArray(String[]::new)), "manifest.json")
        );

        assertTrue(error.getMessage().contains("data count exceeds 100"));
    }

    @Test
    void rejectsMoreThanTenThousandSamples() {
        StringBuilder sampleJson = new StringBuilder();
        for (int i = 0; i < 10_001; i++) {
            if (i > 0) {
                sampleJson.append(',');
            }
            sampleJson.append("{\"external_id\":\"sample_").append(i).append("\"}");
        }
        String json = "{\"version\":\"1.0\",\"samples\":[" + sampleJson + "]}";

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("samples count exceeds 10000"));
    }

    @Test
    void addsWarningForUndeclaredZipFile() {
        String json = manifest(samples(sample("scene_001", 0, "image.png")));

        ManifestImportPlan plan = parser.parse(
                json,
                entries("manifest.json", "image.png", "README.txt"),
                "manifest.json"
        );

        assertEquals(List.of("undeclared zip entry: README.txt"), plan.warnings());
    }

    @Test
    void strictManifestRejectsUndeclaredZipFileWithStructuredDetails() {
        String json = manifest(samples(sample("scene_001", 0, "image.png")));

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(
                        json,
                        entries("manifest.json", "image.png", "README.txt"),
                        "manifest.json",
                        0,
                        true
                )
        );

        assertEquals("INVALID_MANIFEST_UNDECLARED_ENTRY", error.getErrorCode());
        assertTrue(error.getMessage().contains("README.txt"));
        assertEquals("README.txt", error.getDetails().get("path"));
    }

    @Test
    void rejectsUnsupportedManifestVersion() {
        String json = "{\"version\":\"2.0\",\"samples\":[{\"external_id\":\"scene\"}]}";

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("field version"));
        assertTrue(error.getMessage().contains("must equal 1.0"));
        assertEquals("version", error.getDetails().get("field"));
        assertEquals("must equal 1.0", error.getDetails().get("reason"));
    }

    @Test
    void invalidJsonIncludesSafeLocationDetails() {
        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(
                        "{\"version\":\"1.0\",\"samples\":[}",
                        entries("manifest.json"),
                        "manifest.json"
                )
        );

        assertEquals("INVALID_MANIFEST", error.getErrorCode());
        assertEquals("manifestJson", error.getDetails().get("field"));
        assertEquals("invalid JSON", error.getDetails().get("reason"));
        assertTrue(((Number) error.getDetails().get("line")).intValue() >= 1);
        assertTrue(((Number) error.getDetails().get("column")).intValue() >= 1);
    }

    @Test
    void rejectsNonObjectTagsAndMetadata() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"scene",
                    "tags":["bad"]
                  }]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("field tags"));
        assertTrue(error.getMessage().contains("external_id: scene"));
    }

    @Test
    void rejectsDuplicateDataTupleWithinSample() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"scene",
                    "data":[
                      {"path":"first.png","data_type":"IMAGE","sensor":"CAM","channel":"RGB","seq":0},
                      {"path":"second.png","data_type":"IMAGE","sensor":"CAM","channel":"RGB","seq":0}
                    ]
                  }]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(
                        json,
                        entries("manifest.json", "first.png", "second.png"),
                        "manifest.json"
                )
        );

        assertTrue(error.getMessage().contains("duplicate data_type + sensor + channel + seq"));
    }

    @Test
    void rejectsNegativeSequence() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"scene",
                    "data":[{"path":"image.png","data_type":"IMAGE","seq":-1}]
                  }]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "image.png"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("field seq"));
        assertTrue(error.getMessage().contains("greater than or equal to zero"));
    }

    @Test
    void rejectsAnnotationWithoutRequiredFormat() {
        String json = """
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"scene",
                    "annotations":[{"path":"label.json","annotation_type":"BBOX"}]
                  }]
                }
                """;

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json", "label.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("field format"));
        assertTrue(error.getMessage().contains("path: label.json"));
    }

    @Test
    void rejectsMoreThanOneHundredThousandReferencesBeforePathResolution() {
        String repeatedItems = String.join(",", java.util.Collections.nCopies(100, "{}"));
        String sample = "{\"external_id\":\"sample_%d\",\"data\":[%s],\"annotations\":[%s]}";
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            if (i > 0) {
                samples.append(',');
            }
            samples.append(sample.formatted(i, repeatedItems, repeatedItems));
        }
        String json = "{\"version\":\"1.0\",\"samples\":[" + samples + "]}";

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> parser.parse(json, entries("manifest.json"), "manifest.json")
        );

        assertTrue(error.getMessage().contains("references exceed 100000"));
    }

    private static String manifest(String samples) {
        return "{\"version\":\"1.0\",\"samples\":[" + samples + "]}";
    }

    private static String samples(String... samples) {
        return String.join(",", samples);
    }

    private static String sample(String externalId, Integer sampleIndex, String path) {
        String index = sampleIndex == null ? "" : ",\"sample_index\":" + sampleIndex;
        return "{\"external_id\":\"" + externalId + "\"" + index
                + ",\"data\":[{\"path\":\"" + path + "\",\"data_type\":\"IMAGE\"}]}";
    }

    private static List<ZipEntryInfo> entries(String... paths) {
        List<ZipEntryInfo> entries = new ArrayList<>();
        long offset = 0;
        for (String path : paths) {
            entries.add(new ZipEntryInfo(
                    path,
                    path,
                    0,
                    1,
                    1,
                    0,
                    offset,
                    offset + 30,
                    false,
                    path.endsWith("/")
            ));
            offset += 100;
        }
        return entries;
    }
}
