package com.tss.platform.service;

import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeVersionArchiveReaderTest {

    private static final String OBJECT_NAME = "users/7/codes/asset-1/base.zip";

    private final ZipCentralDirectoryReader centralDirectoryReader = mock(ZipCentralDirectoryReader.class);
    private final MinioService minioService = mock(MinioService.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final CodePathPolicy pathPolicy = new CodePathPolicy();
    private final CodeFilePolicy filePolicy = new CodeFilePolicy();

    private CodeVersionArchiveReader reader;

    @BeforeEach
    void setUp() {
        reader = new CodeVersionArchiveReader(
                centralDirectoryReader,
                minioService,
                authContext,
                pathPolicy,
                filePolicy
        );
        doReturn(true).when(authContext).canAccessObjectName(OBJECT_NAME, 7);
    }

    @Test
    void listsSafeFileMetadataWithoutReadingEntryBodies() throws Exception {
        CodeVersion version = version();
        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                zipEntry("src/", "src/", 0, 0, 0, 0, true),
                zipEntry("src/train.py", "src/train.py", 0, 12, 12, 123, false),
                zipEntry("README.md", "README.md", 8, 6, 20, 456, false)
        ));

        List<CodeArchiveEntry> entries = reader.list(version, 7);

        assertEquals(List.of("README.md", "src/train.py"),
                entries.stream().map(CodeArchiveEntry::path).toList());
        verify(centralDirectoryReader).read(OBJECT_NAME, 4096L, 10_000);
        verify(minioService, never()).downloadRange(eq(OBJECT_NAME), anyLong(), anyLong());
    }

    @Test
    void listingRejectsUnsupportedPathsTreeConflictsAndDeclaredTotalLimitGenerically() throws Exception {
        CodeVersion version = version();
        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                zipEntry("run.bin", "run.bin", 0, 1, 1, 1, false)
        ));
        assertGenericAccess(() -> reader.list(version, 7));

        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                zipEntry("src.py", "src.py", 0, 1, 1, 1, false),
                zipEntry("src.py/a.txt", "src.py/a.txt", 0, 1, 1, 2, false)
        ));
        assertGenericAccess(() -> reader.list(version, 7));

        long tooLarge = 512L * 1024 * 1024 + 1;
        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                zipEntry("huge.txt", "huge.txt", 0, tooLarge, tooLarge, 3, false)
        ));
        assertGenericAccess(() -> reader.list(version, 7));
    }

    @Test
    void listingNormalizesWindowsSeparatorsButRejectsOtherAmbiguousRawNames() throws Exception {
        CodeVersion version = version();
        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                zipEntry("src\\train.py", "src/train.py", 0, 1, 1, 1, false)
        ));
        assertEquals(
                List.of("src/train.py"),
                reader.list(version, 7).stream().map(CodeArchiveEntry::path).toList()
        );

        for (String raw : List.of(
                "src//train.py",
                "src/./train.py",
                "src/../train.py",
                "..\\train.py"
        )) {
            when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000)).thenReturn(List.of(
                    zipEntry(raw, "src/train.py", 0, 1, 1, 1, false)
            ));
            assertGenericAccess(() -> reader.list(version, 7));
        }
    }

    @Test
    void readsStoredEntryAndVerifiesExactLengthAndCrc() throws Exception {
        byte[] raw = "line1\r\nline2\n".getBytes(StandardCharsets.UTF_8);
        CodeArchiveEntry entry = archiveEntry("notes.txt", 0, raw, raw.length, 100L);
        when(minioService.downloadRange(OBJECT_NAME, 100L, raw.length))
                .thenReturn(new ByteArrayInputStream(raw));

        byte[] read = reader.read(version(), 7, entry, CodeFilePolicy.EDITABLE_LIMIT_BYTES);

        assertArrayEquals(raw, read);
        raw[0] = 'X';
        assertEquals('l', read[0]);
    }

    @Test
    void rejectsEntryRangeOutsideVersionObjectBeforeStorageCall() throws Exception {
        CodeArchiveEntry outside = new CodeArchiveEntry(
                "notes.txt",
                0,
                3,
                3,
                0,
                4095L
        );

        assertGenericAccess(() -> reader.read(version(), 7, outside, 100));
        verify(minioService, never()).downloadRange(eq(OBJECT_NAME), anyLong(), anyLong());
    }

    @Test
    void readsRawDeflateAndReleasesInflaterAfterCrcVerification() throws Exception {
        byte[] raw = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = rawDeflate(raw);
        CodeArchiveEntry entry = archiveEntry("train.py", 8, compressed, raw.length, 200L);
        when(minioService.downloadRange(OBJECT_NAME, 200L, compressed.length))
                .thenReturn(new ByteArrayInputStream(compressed));

        assertArrayEquals(
                raw,
                reader.read(version(), 7, entry, CodeFilePolicy.EDITABLE_LIMIT_BYTES)
        );
    }

    @Test
    void streamsLargeEntryHashBeyondPreviewLimitWithLengthAndCrcChecks() throws Exception {
        byte[] raw = new byte[(int) CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1];
        java.util.Arrays.fill(raw, (byte) 'x');
        CodeArchiveEntry entry = archiveEntry("large.txt", 0, raw, raw.length, 100L);
        CodeVersion version = version();
        version.setSizeBytes(entry.zipDataOffset() + entry.compressedSize());
        when(minioService.downloadRange(OBJECT_NAME, 100L, raw.length))
                .thenReturn(new ByteArrayInputStream(raw));

        assertEquals(filePolicy.sha256(raw), reader.sha256(version, 7, entry));

        CodeArchiveEntry wrongCrc = new CodeArchiveEntry(
                entry.path(), entry.method(), entry.compressedSize(),
                entry.uncompressedSize(), entry.crc32() + 1, entry.zipDataOffset()
        );
        when(minioService.downloadRange(OBJECT_NAME, 100L, raw.length))
                .thenReturn(new ByteArrayInputStream(raw));
        assertGenericAccess(() -> reader.sha256(version, 7, wrongCrc));
    }

    @Test
    void streamsDeflatedEntryHashAndRejectsEarlyEof() throws Exception {
        byte[] raw = "print('hash')\n".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = rawDeflate(raw);
        CodeArchiveEntry entry = new CodeArchiveEntry(
                "train.py", 8, compressed.length, raw.length, crc(raw), 200L
        );
        when(minioService.downloadRange(OBJECT_NAME, 200L, compressed.length))
                .thenReturn(new ByteArrayInputStream(compressed));
        assertEquals(filePolicy.sha256(raw), reader.sha256(version(), 7, entry));

        byte[] truncated = java.util.Arrays.copyOf(compressed, compressed.length - 1);
        when(minioService.downloadRange(OBJECT_NAME, 200L, compressed.length))
                .thenReturn(new ByteArrayInputStream(truncated));
        assertGenericAccess(() -> reader.sha256(version(), 7, entry));
    }

    @Test
    void rejectsOversizeBeforeRangeAndWrapsEarlyEofLengthAndCrcWithoutPathLeak() throws Exception {
        CodeArchiveEntry tooLarge = new CodeArchiveEntry(
                "large.txt",
                0,
                CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1,
                CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1,
                0,
                300L
        );
        CodeVersion largeVersion = version();
        largeVersion.setSizeBytes(tooLarge.zipDataOffset() + tooLarge.compressedSize());
        assertThrows(
                CodeContentTooLargeException.class,
                () -> reader.read(
                        largeVersion,
                        7,
                        tooLarge,
                        CodeFilePolicy.EDITABLE_LIMIT_BYTES
                )
        );
        verify(minioService, never()).downloadRange(eq(OBJECT_NAME), eq(300L), anyLong());

        byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
        CodeArchiveEntry stored = archiveEntry("a.txt", 0, raw, raw.length, 400L);
        when(minioService.downloadRange(OBJECT_NAME, 400L, raw.length))
                .thenReturn(new ByteArrayInputStream(new byte[]{'a', 'b'}));
        assertGenericAccess(() -> reader.read(version(), 7, stored, 100));

        CodeArchiveEntry wrongLength = new CodeArchiveEntry(
                "a.txt", 0, raw.length, raw.length + 1L, crc(raw), 400L
        );
        when(minioService.downloadRange(OBJECT_NAME, 400L, raw.length))
                .thenReturn(new ByteArrayInputStream(raw));
        assertGenericAccess(() -> reader.read(version(), 7, wrongLength, 100));

        CodeArchiveEntry wrongCrc = new CodeArchiveEntry(
                "a.txt", 0, raw.length, raw.length, crc(raw) + 1, 400L
        );
        assertGenericAccess(() -> reader.read(version(), 7, wrongCrc, 100));
    }

    @Test
    void checksObjectAuthorizationBeforeAnyStorageCallAndSanitizesFailures() throws Exception {
        CodeVersion version = version();
        when(authContext.canAccessObjectName(OBJECT_NAME, 7)).thenReturn(false);

        CodeAssetAccessException inaccessible = assertThrows(
                CodeAssetAccessException.class,
                () -> reader.list(version, 7)
        );
        assertFalse(inaccessible.getMessage().contains(OBJECT_NAME));
        verify(centralDirectoryReader, never()).read(eq(OBJECT_NAME), anyLong(), anyInt());

        when(authContext.canAccessObjectName(OBJECT_NAME, 7))
                .thenThrow(new IllegalStateException("authorization failed for " + OBJECT_NAME));
        CodeAssetAccessException authFailure = assertThrows(
                CodeAssetAccessException.class,
                () -> reader.list(version, 7)
        );
        assertFalse(authFailure.getMessage().contains(OBJECT_NAME));

        doReturn(true).when(authContext).canAccessObjectName(OBJECT_NAME, 7);
        when(centralDirectoryReader.read(OBJECT_NAME, 4096L, 10_000))
                .thenThrow(new IllegalArgumentException("failed for " + OBJECT_NAME));
        CodeAssetAccessException storageFailure = assertThrows(
                CodeAssetAccessException.class,
                () -> reader.list(version, 7)
        );
        assertFalse(storageFailure.getMessage().contains(OBJECT_NAME));
    }

    private static void assertGenericAccess(ThrowingAction action) {
        CodeAssetAccessException error = assertThrows(CodeAssetAccessException.class, action::run);
        assertFalse(error.getMessage().contains(OBJECT_NAME));
    }

    private static CodeVersion version() {
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setStoragePath(OBJECT_NAME);
        version.setSizeBytes(4096L);
        version.setDeleted(false);
        return version;
    }

    private static ZipEntryInfo zipEntry(
            String path,
            String normalized,
            int method,
            long compressedSize,
            long uncompressedSize,
            long dataOffset,
            boolean directory
    ) {
        return new ZipEntryInfo(
                path,
                normalized,
                method,
                compressedSize,
                uncompressedSize,
                0,
                0,
                dataOffset,
                false,
                directory
        );
    }

    private static CodeArchiveEntry archiveEntry(
            String path,
            int method,
            byte[] compressed,
            long uncompressedSize,
            long dataOffset
    ) {
        byte[] raw;
        if (method == 0) {
            raw = compressed;
        } else {
            raw = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        }
        return new CodeArchiveEntry(
                path,
                method,
                compressed.length,
                uncompressedSize,
                crc(raw),
                dataOffset
        );
    }

    private static long crc(byte[] raw) {
        CRC32 crc = new CRC32();
        crc.update(raw);
        return crc.getValue();
    }

    private static byte[] rawDeflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
