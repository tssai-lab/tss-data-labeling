package com.tss.platform.service;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetZipValidatorContentTest {

    @TestFactory
    Stream<DynamicTest> rejectsAllTwentyEightCvAndNlpNegativeInputs() throws Exception {
        byte[] validPng = png();
        byte[] validJpeg = image("jpeg");
        byte[] yolo = "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8);
        List<NegativeCase> cases = List.of(
                negative("cv-empty-zip", () -> validateZip("CV", "NONE")),
                negative("cv-annotation-without-image", () -> validateZip(
                        "CV", "YOLO", entry("labels/cat.txt", yolo)
                )),
                negative("cv-unsupported-extension", () -> validateZip(
                        "CV", "NONE",
                        entry("cat.png", validPng),
                        entry("payload.exe", new byte[]{'M', 'Z'})
                )),
                negative("cv-path-traversal", () -> validateZip(
                        "CV", "NONE", entry("../cat.png", validPng)
                )),
                negative("cv-absolute-path", () -> validateZip(
                        "CV", "NONE", entry("/cat.png", validPng)
                )),
                negative("cv-windows-drive-path", () -> validateZip(
                        "CV", "NONE", entry("C:/cat.png", validPng)
                )),
                negative("cv-slash-alias-duplicate", () -> validateZip(
                        "CV", "NONE",
                        entry("images/cat.png", validPng),
                        entry("images\\cat.png", validPng)
                )),
                negative("cv-fake-image", () -> validateZip(
                        "CV", "NONE",
                        entry("cat.jpg", "not an image".getBytes(StandardCharsets.UTF_8))
                )),
                negative("cv-image-extension-mismatch", () -> validateZip(
                        "CV", "NONE", entry("cat.jpg", validPng)
                )),
                negative("cv-empty-image", () -> validateZip(
                        "CV", "NONE", entry("cat.png", new byte[0])
                )),
                negative("cv-yolo-missing-label", () -> validateZip(
                        "CV", "YOLO", entry("images/cat.jpg", validJpeg)
                )),
                negative("cv-yolo-orphan-label", () -> validateZip(
                        "CV", "YOLO",
                        entry("images/cat.jpg", validJpeg),
                        entry("labels/cat.txt", yolo),
                        entry("labels/dog.txt", yolo)
                )),
                negative("cv-yolo-mismatched-pair", () -> validateZip(
                        "CV", "YOLO",
                        entry("images/cat.jpg", validJpeg),
                        entry("labels/dog.txt", yolo)
                )),
                negative("cv-coco-malformed-json", () -> validateZip(
                        "CV", "COCO",
                        entry("images/cat.png", validPng),
                        entry("annotations.json", "{\"images\":".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-image-extension-single-file", () ->
                        DatasetUploadService.validateDatasetFileNameForTask("NLP", "image.jpg")
                ),
                negative("nlp-empty-zip", () -> validateZip("NLP", null)),
                negative("nlp-unsupported-extension", () -> validateZip(
                        "NLP", null, entry("payload.bin", new byte[]{1, 2, 3})
                )),
                negative("nlp-path-traversal", () -> validateZip(
                        "NLP", null,
                        entry("../document.txt", "text".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-windows-drive-path", () -> validateZip(
                        "NLP", null,
                        entry("C:/document.txt", "text".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-slash-alias-duplicate", () -> validateZip(
                        "NLP", null,
                        entry("docs/document.txt", "one".getBytes(StandardCharsets.UTF_8)),
                        entry("docs\\document.txt", "two".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-invalid-utf8", () -> validateZip(
                        "NLP", null, entry("document.txt", new byte[]{(byte) 0xC3, 0x28})
                )),
                negative("nlp-nul-control-byte", () -> validateZip(
                        "NLP", null, entry("document.txt", new byte[]{'a', 0, 'b'})
                )),
                negative("nlp-image-content-as-text", () -> validateZip(
                        "NLP", null, entry("image.txt", validPng)
                )),
                negative("nlp-zip-content-as-text", () -> validateZip(
                        "NLP", null,
                        entry("archive.txt", new byte[]{'P', 'K', 0x03, 0x04, 'a'})
                )),
                negative("nlp-malformed-json", () -> validateZip(
                        "NLP", null,
                        entry("document.json", "{\"text\":".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-json-trailing-document", () -> validateZip(
                        "NLP", null,
                        entry(
                                "document.json",
                                "{\"a\":1} {\"b\":2}".getBytes(StandardCharsets.UTF_8)
                        )
                )),
                negative("nlp-malformed-jsonl-line", () -> validateZip(
                        "NLP", null,
                        entry("document.jsonl", "{\"ok\":1}\n{bad}\n".getBytes(StandardCharsets.UTF_8))
                )),
                negative("nlp-empty-jsonl", () -> validateZip(
                        "NLP", null,
                        entry("document.jsonl", " \n\t\n".getBytes(StandardCharsets.UTF_8))
                ))
        );
        return cases.stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.name(),
                () -> assertThrows(IllegalArgumentException.class, testCase.executable())
        ));
    }

    @Test
    void rejectsYoloImageAndLabelMismatch() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "CV",
                "YOLO",
                entry("images/train/cat.png", png()),
                entry("labels/train/dog.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Test
    void acceptsYoloFlatAndMirroredLayouts() throws Exception {
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "YOLO",
                entry("cat.png", png()),
                entry("cat.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8))
        ));
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "YOLO",
                entry("train.jpg", image("jpeg")),
                entry("train.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8)),
                entry("classes.txt", "cat".getBytes(StandardCharsets.UTF_8))
        ));
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "YOLO",
                entry("images/train/cat.png", png()),
                entry("labels/train/cat.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8)),
                entry("classes.txt", "cat".getBytes(StandardCharsets.UTF_8))
        ));
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "YOLO",
                entry("train/images/frame.png", png()),
                entry("train/labels/frame.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8)),
                entry("val/images/frame.png", png()),
                entry("val/labels/frame.txt", "0 0.5 0.5 1 1".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @TestFactory
    Stream<DynamicTest> rejectsMalformedYoloLabelRows() throws Exception {
        byte[] validPng = png();
        List<String> invalidRows = List.of(
                "0 0.5 0.5 1",
                "0 0.5 0.5 1 1 extra",
                "-1 0.5 0.5 1 1",
                "1.5 0.5 0.5 1 1",
                "2147483648 0.5 0.5 1 1",
                "0 NaN 0.5 1 1",
                "0 Infinity 0.5 1 1",
                "0 -0.01 0.5 1 1",
                "0 1.01 0.5 1 1",
                "0 0.5 0.5 0 1",
                "0 0.5 0.5 1 1.01"
        );
        return invalidRows.stream().map(row -> DynamicTest.dynamicTest(
                "rejects-yolo-row-" + row,
                () -> assertThrows(IllegalArgumentException.class, () -> validateZip(
                        "CV",
                        "YOLO",
                        entry("images/cat.png", validPng),
                        entry(
                                "labels/cat.txt",
                                row.getBytes(StandardCharsets.UTF_8)
                        )
                ))
        ));
    }

    @Test
    void acceptsMultipleYoloRowsAndFiniteScientificNotation() throws Exception {
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "YOLO",
                entry("images/cat.png", png()),
                entry(
                        "labels/cat.txt",
                        """
                                0 0 1 1 1
                                12 5e-1 0.25 1e-3 9.9E-1

                                """.getBytes(StandardCharsets.UTF_8)
                )
        ));
    }

    @Test
    void acceptsRootManifestForFolderClassificationDataset() throws Exception {
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "FOLDER_CLASSIFICATION",
                entry(
                        "dataset.yaml",
                        "schemaVersion: tss.dataset/v1\nsplits: [train, validation, test]\n"
                                .getBytes(StandardCharsets.UTF_8)
                ),
                entry("data/train/healthy/sample.png", png())
        ));
    }

    @Test
    void rejectsArbitraryYamlInsideFolderClassificationDataset() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "CV",
                "FOLDER_CLASSIFICATION",
                entry("config/payload.yaml", "key: value\n".getBytes(StandardCharsets.UTF_8)),
                entry("data/train/healthy/sample.png", png())
        ));
    }

    @Test
    void rejectsFakeAndMismatchedImageContent() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "CV",
                "NONE",
                entry("fake.jpg", "not an image".getBytes(StandardCharsets.UTF_8))
        ));
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "CV",
                "NONE",
                entry("mismatch.jpg", png())
        ));
    }

    @Test
    void acceptsDecodableJpegPngTiffAndWebp() throws Exception {
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "NONE",
                entry("image.jpg", image("jpeg"))
        ));
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "NONE",
                entry("image.png", png())
        ));
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "NONE",
                entry("image.tiff", image("tiff"))
        ));
        byte[] webp = Base64.getDecoder().decode(
                "UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA"
        );
        assertDoesNotThrow(() -> validateZip(
                "CV",
                "NONE",
                entry("image.webp", webp)
        ));
    }

    @Test
    void rejectsMalformedJsonAndJsonLines() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "NLP",
                null,
                entry("broken.json", "{\"text\":".getBytes(StandardCharsets.UTF_8))
        ));
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "NLP",
                null,
                entry("broken.jsonl", "{\"ok\":1}\n{bad}\n".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Test
    void acceptsValidNlpTextJsonAndJsonLines() throws Exception {
        assertDoesNotThrow(() -> validateZip(
                "NLP",
                null,
                entry("text.txt", "hello 世界".getBytes(StandardCharsets.UTF_8)),
                entry("data.json", "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8)),
                entry("data.jsonl", "{\"id\":1}\n{\"id\":2}\n".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Test
    void rejectsInvalidUtf8AndImageMasqueradingAsText() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "NLP",
                null,
                entry("invalid.txt", new byte[]{(byte) 0xC3, 0x28})
        ));
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "NLP",
                null,
                entry("image.txt", png())
        ));
        assertThrows(IllegalArgumentException.class, () -> validateZip(
                "NLP",
                null,
                entry("archive.txt", new byte[]{'P', 'K', 0x03, 0x04, 'a', 'b'})
        ));
    }

    @Test
    void rejectsImageExtensionForSingleNlpUpload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetFileNameForTask("NLP", "image.jpg")
        );
    }

    private static void validateZip(String taskType, String annotationFormat, Entry... entries)
            throws Exception {
        DatasetUploadService.validateDatasetZipEntries(
                taskType,
                annotationFormat,
                new ByteArrayInputStream(zip(entries))
        );
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

    private static byte[] png() throws Exception {
        return image("png");
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, bytes)) {
            throw new IllegalStateException("ImageIO writer unavailable: " + format);
        }
        return bytes.toByteArray();
    }

    private static Entry entry(String name, byte[] content) {
        return new Entry(name, content);
    }

    private static NegativeCase negative(String name, Executable executable) {
        return new NegativeCase(name, executable);
    }

    private record NegativeCase(String name, Executable executable) {
    }

    private record Entry(String name, byte[] content) {
    }
}
