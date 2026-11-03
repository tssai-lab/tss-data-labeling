package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManifestZipReaderTest {

    @Test
    void readsStoredManifest() throws Exception {
        assertManifestRead(ZipTestFixtures.stored("manifest.json", "{\"stored\":true}"),
                "{\"stored\":true}");
    }

    @Test
    void readsDeflatedManifest() throws Exception {
        assertManifestRead(ZipTestFixtures.deflated("manifest.json", "{\"deflated\":true}"),
                "{\"deflated\":true}");
    }

    @Test
    void readsManifestWhenRequestedPathUsesBackslashes() throws Exception {
        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.stored("manifests/manifest.json", "{\"nested\":true}")
        );
        ManifestZipReader reader = readerFor(zip);

        assertEquals(
                "{\"nested\":true}",
                reader.readManifest("dataset.zip", zip.length, "manifests\\manifest.json")
        );
    }

    @Test
    void rejectsMissingManifest() throws Exception {
        byte[] zip = ZipTestFixtures.zip(ZipTestFixtures.stored("other.json", "{}"));
        ManifestZipReader reader = readerFor(zip);

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> reader.readManifest("dataset.zip", zip.length, "manifest.json"));

        assertEquals("INVALID_MANIFEST", error.getErrorCode());
        assertEquals("manifestPath", error.getDetails().get("field"));
        assertEquals("manifest.json", error.getDetails().get("path"));
        assertEquals(
                "manifest entry not found",
                error.getDetails().get("reason")
        );
    }

    @Test
    void reusesManifestPathSafetyRules() throws Exception {
        byte[] zip = ZipTestFixtures.zip(ZipTestFixtures.stored("manifest.json", "{}"));
        ManifestZipReader reader = readerFor(zip);

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> reader.readManifest("dataset.zip", zip.length, "../manifest.json"));

        assertEquals("manifestPath", error.getDetails().get("field"));
        assertEquals("../manifest.json", error.getDetails().get("path"));
    }

    @Test
    void rejectsManifestAboveTenMegabytes() throws Exception {
        byte[] content = new byte[10 * 1024 * 1024 + 1];
        byte[] zip = ZipTestFixtures.zip(
                new ZipTestFixtures.EntrySpec("manifest.json", content, java.util.zip.ZipEntry.STORED)
        );
        ManifestZipReader reader = readerFor(zip);

        ManifestValidationException error = assertThrows(
                ManifestValidationException.class,
                () -> reader.readManifest("dataset.zip", zip.length, "manifest.json"));

        assertEquals("manifestPath", error.getDetails().get("field"));
        assertEquals("manifest exceeds 10MB", error.getDetails().get("reason"));
    }

    private static void assertManifestRead(ZipTestFixtures.EntrySpec spec, String expected) throws Exception {
        byte[] zip = ZipTestFixtures.zip(spec);
        ManifestZipReader reader = readerFor(zip);

        assertEquals(expected, reader.readManifest("dataset.zip", zip.length, "manifest.json"));
    }

    private static ManifestZipReader readerFor(byte[] object) throws Exception {
        MinioService minioService = mock(MinioService.class);
        when(minioService.downloadRange(eq("dataset.zip"), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    long offset = invocation.getArgument(1);
                    long length = invocation.getArgument(2);
                    int start = Math.toIntExact(offset);
                    int end = Math.toIntExact(offset + length);
                    return new ByteArrayInputStream(java.util.Arrays.copyOfRange(object, start, end));
                });
        return new ManifestZipReader(new ZipCentralDirectoryReader(minioService), minioService);
    }
}
