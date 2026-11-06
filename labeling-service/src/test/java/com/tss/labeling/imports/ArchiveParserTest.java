package com.tss.labeling.imports;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveParserTest {
    @TempDir Path root;

    ArchiveParser parser(int entries, long fileBytes, long expandedBytes, long pixels) throws Exception {
        ImportSettings settings = new ImportSettings(root.toString(), 256, 1048576, expandedBytes, fileBytes, entries, pixels, 2, "UTF-8", false);
        return new ArchiveParser(settings, new ImportStorage(settings));
    }

    UUID archive(byte[] bytes) throws Exception {
        UUID batch = UUID.randomUUID();
        Files.createDirectories(root.resolve(batch.toString()));
        Files.write(root.resolve(batch + "/archive.zip"), bytes);
        return batch;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../out.png", "/out.png", "C:/out.png", "dir\\out.png", "x/./out.png", "x//out.png"})
    void rejectsUnsafePaths(String path) throws Exception {
        var rejected = assertThrows(ArchiveParser.Rejected.class, () -> parser(10, 4096, 4096, 10000)
                .parse(archive(ImportIntegrationTest.zip(Map.of(path, ImportIntegrationTest.image("png"))))));
        assertEquals("UNSAFE_ARCHIVE", rejected.code);
    }

    @Test void rejectsSymlinksFromCentralDirectoryAttributes() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(out)) {
            var link = new ZipArchiveEntry("link.png"); link.setUnixMode(0120777);
            zip.putArchiveEntry(link); zip.write("target.png".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeArchiveEntry();
        }
        var rejected = assertThrows(ArchiveParser.Rejected.class, () -> parser(10, 4096, 4096, 10000).parse(archive(out.toByteArray())));
        assertEquals("UNSAFE_ARCHIVE", rejected.code);
    }

    @Test void rejectsCanonicalDuplicatePaths() throws Exception {
        var rejected = assertThrows(ArchiveParser.Rejected.class, () -> parser(10, 4096, 4096, 10000).parse(archive(
                ImportIntegrationTest.zip(Map.of("é.png", ImportIntegrationTest.image("png"), "e\u0301.png", ImportIntegrationTest.image("png"))))));
        assertEquals("UNSAFE_ARCHIVE", rejected.code);
    }

    @Test void enforcesEntryFileAndAggregateExpansionLimits() throws Exception {
        byte[] png = ImportIntegrationTest.image("png");
        byte[] zip = ImportIntegrationTest.zip(Map.of("a.png", png, "b.png", png));
        for (ArchiveParser parser : List.of(parser(1, 4096, 4096, 10000), parser(10, 1, 4096, 10000), parser(10, 4096, png.length, 10000))) {
            assertEquals("EXPANSION_LIMIT", assertThrows(ArchiveParser.Rejected.class, () -> parser.parse(archive(zip))).code);
        }
    }

    @Test void checksPixelDimensionsBeforeDecodingAndCapsReportedErrors() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("a.png", ImportIntegrationTest.image("png")); files.put("b.png", new byte[]{1}); files.put("c.png", new byte[]{2});
        var result = parser(10, 4096, 4096, 10).parse(archive(ImportIntegrationTest.zip(files)));
        assertTrue(result.samples().isEmpty()); assertEquals(3, result.errorCount()); assertEquals(2, result.errors().size());
        assertEquals("IMAGE_TOO_LARGE", result.errors().get(0).code());
    }

    @Test void rejectsForgedDirectoryCountBeforeLibraryAllocation() throws Exception {
        byte[] bytes = ImportIntegrationTest.zip(Map.of("a.png", ImportIntegrationTest.image("png")));
        // EOCD total entry count advertises 200 entries despite containing one.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(bytes.length - 12, (short) 200).putShort(bytes.length - 14, (short) 200);
        var rejected = assertThrows(ArchiveParser.Rejected.class, () -> parser(2, 4096, 4096, 10000).parse(archive(bytes)));
        assertEquals("EXPANSION_LIMIT", rejected.code);
    }

    @Test void supportsSingleVolumeZip64() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(out)) {
            zip.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.Always);
            zip.putArchiveEntry(new ZipArchiveEntry("one.png")); zip.write(ImportIntegrationTest.image("png")); zip.closeArchiveEntry();
        }
        assertEquals(1, parser(10, 4096, 4096, 10000).parse(archive(out.toByteArray())).samples().size());
    }

    byte[] zip64() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(out)) {
            zip.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.Always);
            for (String name : List.of("one.png", "two.png", "three.png")) {
                zip.putArchiveEntry(new ZipArchiveEntry(name)); zip.write(ImportIntegrationTest.image("png")); zip.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    @Test void rejectsUnderstatedZip64DirectoryBeforeLibraryAllocation() throws Exception {
        byte[] bytes = zip64();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22, record = (int) buffer.getLong(end - 12);
        buffer.putShort(end + 8, (short) 65535).putShort(end + 10, (short) 65535)
                .putInt(end + 12, -1).putInt(end + 16, -1);
        buffer.putLong(record + 24, 0).putLong(record + 32, 0).putLong(record + 40, 0);
        UUID batch = archive(bytes);
        ImportSettings settings = new ImportSettings(root.toString(), 256, 1048576, 4096, 4096, 2, 10000, 2, "UTF-8", false);
        assertThrows(ArchiveParser.Rejected.class, () -> ZipSafety.check(root.resolve(batch + "/archive.zip"), settings));
    }

    @Test void rejectsContradictoryZip32RecordsWhenZip64LocatorExists() throws Exception {
        byte[] bytes = zip64();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        buffer.putShort(end + 8, (short) 0).putShort(end + 10, (short) 0).putInt(end + 12, 0).putInt(end + 16, end);
        UUID batch = archive(bytes);
        ImportSettings settings = new ImportSettings(root.toString(), 256, 1048576, 4096, 4096, 2, 10000, 2, "UTF-8", false);
        assertThrows(ArchiveParser.Rejected.class, () -> ZipSafety.check(root.resolve(batch + "/archive.zip"), settings));
    }
}
