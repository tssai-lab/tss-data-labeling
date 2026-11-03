package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetUploadServiceOtherTest {

    @Test
    void otherAllowsOnlyTheExistingSafeFileFamilies() {
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetFileNameForTask("OTHER", "archive.zip"));
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetFileNameForTask("OTHER", "notes.yaml"));
        assertDoesNotThrow(() ->
                DatasetUploadService.validateDatasetFileNameForTask("OTHER", "scan.ply"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetFileNameForTask("OTHER", "payload.exe")
        );
        assertTrue(error.getMessage().contains("safe file allowlist"));
    }

    @Test
    void otherZipKeepsPathAndExtensionSafetyChecks() throws Exception {
        byte[] safe = zip(entry("archive/notes.yaml", "kind: notes"));
        assertDoesNotThrow(() -> DatasetUploadService.validateDatasetZipEntries(
                "OTHER",
                null,
                new ByteArrayInputStream(safe)
        ));

        byte[] executable = zip(entry("archive/run.exe", "MZ"));
        IllegalArgumentException typeError = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "OTHER",
                        null,
                        new ByteArrayInputStream(executable)
                )
        );
        assertTrue(typeError.getMessage().contains("unsupported file"));

        byte[] traversal = zip(entry("../escape.txt", "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "OTHER",
                        null,
                        new ByteArrayInputStream(traversal)
                )
        );
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private record Entry(String name, String content) {
    }
}
