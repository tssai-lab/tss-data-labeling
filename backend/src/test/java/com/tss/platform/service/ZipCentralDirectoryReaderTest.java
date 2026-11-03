package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZipCentralDirectoryReaderTest {

    @Test
    void parsesCentralDirectoryAndLocalDataOffsets() throws Exception {
        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.stored("manifest.json", "{\"version\":\"1.0\"}"),
                ZipTestFixtures.deflated("samples/image.txt", "sample")
        );
        ZipCentralDirectoryReader reader = readerFor(zip);

        List<ZipEntryInfo> entries = reader.read("dataset.zip", zip.length);

        assertEquals(2, entries.size());
        ZipEntryInfo manifest = entries.get(0);
        assertEquals("manifest.json", manifest.path());
        assertEquals(0, manifest.method());
        assertEquals(17, manifest.uncompressedSize());
        assertTrue(manifest.zipDataOffset() > manifest.localHeaderOffset());
        assertTrue(manifest.zipDataOffset() + manifest.compressedSize() <= zip.length);
        assertEquals(8, entries.get(1).method());
    }

    @Test
    void normalizesBackslashEntryPathsAsDirectorySeparators() throws Exception {
        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.stored("data\\scores.jsonl", "{}")
        );
        ZipCentralDirectoryReader reader = readerFor(zip);

        List<ZipEntryInfo> entries = reader.read("dataset.zip", zip.length);

        assertEquals(1, entries.size());
        assertEquals("data\\scores.jsonl", entries.get(0).path());
        assertEquals("data/scores.jsonl", entries.get(0).normalizedPath());
    }

    @Test
    void treatsBackslashTerminatedEntriesAsDirectories() throws Exception {
        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.stored("data\\", "")
        );
        ZipCentralDirectoryReader reader = readerFor(zip);

        List<ZipEntryInfo> entries = reader.read("dataset.zip", zip.length);

        assertEquals(1, entries.size());
        assertEquals("data/", entries.get(0).normalizedPath());
        assertTrue(entries.get(0).directory());
    }

    @Test
    void rejectsParentDirectoryTraversal() throws Exception {
        byte[] zip = ZipTestFixtures.zip(ZipTestFixtures.stored("../manifest.json", "{}"));

        assertThrows(IllegalArgumentException.class,
                () -> readerFor(zip).read("dataset.zip", zip.length));
    }

    @Test
    void rejectsDuplicateNormalizedPaths() throws Exception {
        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.stored("a//manifest.json", "{}"),
                ZipTestFixtures.stored("a/./manifest.json", "{}")
        );

        assertThrows(IllegalArgumentException.class,
                () -> readerFor(zip).read("dataset.zip", zip.length));
    }

    @Test
    void rejectsUnsupportedCompressionMethod() throws Exception {
        byte[] zip = ZipTestFixtures.zip(ZipTestFixtures.stored("manifest.json", "{}"));
        byte[] patched = ZipTestFixtures.patchCompressionMethod(zip, 99);

        assertThrows(IllegalArgumentException.class,
                () -> readerFor(patched).read("dataset.zip", patched.length));
    }

    private static ZipCentralDirectoryReader readerFor(byte[] object) throws Exception {
        MinioService minioService = mock(MinioService.class);
        when(minioService.downloadRange(eq("dataset.zip"), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    long offset = invocation.getArgument(1);
                    long length = invocation.getArgument(2);
                    int start = Math.toIntExact(offset);
                    int end = Math.toIntExact(offset + length);
                    return new ByteArrayInputStream(java.util.Arrays.copyOfRange(object, start, end));
                });
        return new ZipCentralDirectoryReader(minioService);
    }
}
