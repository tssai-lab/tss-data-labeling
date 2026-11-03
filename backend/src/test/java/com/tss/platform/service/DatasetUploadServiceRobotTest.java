package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetUploadServiceRobotTest {

    @Test
    void robotDatasetAllowsXmlYamlAndZipFiles() {
        DatasetUploadService.validateDatasetFileNameForTask("ROBOT", "task.xml");
        DatasetUploadService.validateDatasetFileNameForTask("ROBOT", "task.yaml");
        DatasetUploadService.validateDatasetFileNameForTask("ROBOT", "task.yml");
        DatasetUploadService.validateDatasetFileNameForTask("ROBOT", "robot.zip");
    }

    @Test
    void robotDatasetRejectsUnsupportedFileNames() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetFileNameForTask("ROBOT", "robot.exe")
        );

        assertTrue(error.getMessage().contains("ROBOT"));
    }

    @Test
    void robotZipAllowsXmlYamlJsonAndTextOnly() throws Exception {
        byte[] zip = zip(
                entry("config.yaml", "arm: a"),
                entry("metadata.json", "{}"),
                entry("readme.txt", "notes")
        );

        DatasetUploadService.validateDatasetZipEntries(
                "ROBOT",
                null,
                new ByteArrayInputStream(zip)
        );
    }

    @Test
    void robotZipRejectsBinaryEntries() throws Exception {
        byte[] zip = zip(entry("payload.bin", "bad"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DatasetUploadService.validateDatasetZipEntries(
                        "ROBOT",
                        null,
                        new ByteArrayInputStream(zip)
                )
        );

        assertTrue(error.getMessage().contains("ROBOT zip dataset only allows"));
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
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

    private record Entry(String name, String content) {
    }
}
