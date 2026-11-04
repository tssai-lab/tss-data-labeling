package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeZipArchiveServiceTest {

    private final CodeZipArchiveService service = new CodeZipArchiveService();

    @Test
    void readsValidatedFilesInLexicalOrder() throws Exception {
        byte[] zip = buildZip(
                entry("z.txt", "last"),
                entry("src/train.py", "print('ok')"),
                entry("config/model.yaml", "model: baseline\n")
        );

        LinkedHashMap<String, byte[]> files = service.readEntries(new ByteArrayInputStream(zip));

        assertEquals(List.of("config/model.yaml", "src/train.py", "z.txt"), new ArrayList<>(files.keySet()));
        assertArrayEquals("print('ok')".getBytes(StandardCharsets.UTF_8), files.get("src/train.py"));
    }

    @Test
    void rejectsEmptyArchive() throws Exception {
        assertReason("ZIP_EMPTY", () -> service.readEntries(new ByteArrayInputStream(buildZip())));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        byte[] zip = buildZip(entry("weights/model.bin", "binary"));

        assertReason("UNSUPPORTED_EXTENSION", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsMalformedUtf8Text() throws Exception {
        byte[] zip = buildZip(new EntrySpec("broken.py", new byte[]{(byte) 0xC3, 0x28}));

        assertReason("INVALID_UTF8", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() throws Exception {
        byte[] traversal = buildZip(entry("../train.py", "pass"));
        byte[] absolute = buildZip(entry("C:\\train.py", "pass"));

        assertReason("INVALID_PATH", () -> service.readEntries(new ByteArrayInputStream(traversal)));
        assertReason("INVALID_PATH", () -> service.readEntries(new ByteArrayInputStream(absolute)));
    }

    @Test
    void rejectsExactDuplicateEntries() throws Exception {
        byte[] exactDuplicate = buildRawStoredZip(
                rawEntry("train.py", "one"),
                rawEntry("train.py", "two")
        );

        assertReason("DUPLICATE_PATH", () -> service.readEntries(new ByteArrayInputStream(exactDuplicate)));
    }

    @Test
    void acceptsWindowsSeparatorsButRejectsOtherUnsafeRawAliases() throws Exception {
        byte[] windowsArchive = buildRawStoredZip(
                rawEntry("src\\train.py", "pass"),
                rawEntry("config\\model.yaml", "epochs: 2\n")
        );
        LinkedHashMap<String, byte[]> normalized = service.readEntries(
                new ByteArrayInputStream(windowsArchive)
        );
        assertEquals(List.of("config/model.yaml", "src/train.py"),
                new ArrayList<>(normalized.keySet()));

        List<String> invalidNames = List.of(
                "src//train.py",
                "./train.py",
                "src/../train.py",
                "..\\train.py",
                "C:\\train.py",
                "\\\\server\\share\\train.py",
                "src/\u0001train.py"
        );

        for (String invalidName : invalidNames) {
            byte[] archive = buildRawStoredZip(rawEntry(invalidName, "pass"));
            assertReason(
                    "INVALID_PATH",
                    () -> service.readEntries(new ByteArrayInputStream(archive)),
                    "raw file name accepted: " + printable(invalidName)
            );
        }
    }

    @Test
    void acceptsWindowsDirectorySeparatorsButRejectsUnsafeRawDirectories() throws Exception {
        byte[] windowsDirectory = buildRawStoredZip(
                rawEntry("src\\", ""),
                rawEntry("src\\train.py", "pass")
        );
        assertEquals(
                List.of("src/train.py"),
                new ArrayList<>(service.readEntries(
                        new ByteArrayInputStream(windowsDirectory)
                ).keySet())
        );

        List<String> invalidDirectories = List.of(
                "src\\/",
                "src//",
                "./",
                "src/../",
                "src/\u0001/"
        );

        for (String invalidDirectory : invalidDirectories) {
            byte[] archive = buildRawStoredZip(
                    rawEntry(invalidDirectory, ""),
                    rawEntry("train.py", "pass")
            );
            assertReason(
                    "INVALID_PATH",
                    () -> service.readEntries(new ByteArrayInputStream(archive)),
                    "raw directory name accepted: " + printable(invalidDirectory)
            );
        }
    }

    @Test
    void rejectsDuplicateDirectoryEntries() throws Exception {
        byte[] duplicateDirectories = buildRawStoredZip(
                rawEntry("src/", ""),
                rawEntry("src/", ""),
                rawEntry("train.py", "pass")
        );

        assertReason(
                "DUPLICATE_PATH",
                () -> service.readEntries(new ByteArrayInputStream(duplicateDirectories))
        );
    }

    @Test
    void rejectsDuplicatesAndTreeConflictsAfterWindowsPathNormalization() throws Exception {
        byte[] duplicateFiles = buildRawStoredZip(
                rawEntry("src\\train.py", "one"),
                rawEntry("src/train.py", "two")
        );
        assertReason("DUPLICATE_PATH",
                () -> service.readEntries(new ByteArrayInputStream(duplicateFiles)));

        byte[] duplicateDirectories = buildRawStoredZip(
                rawEntry("src\\", ""),
                rawEntry("src/", ""),
                rawEntry("train.py", "pass")
        );
        assertReason("DUPLICATE_PATH",
                () -> service.readEntries(new ByteArrayInputStream(duplicateDirectories)));

        byte[] treeConflict = buildRawStoredZip(
                rawEntry("src\\train.py", "pass"),
                rawEntry("src/train.py\\config.json", "{}")
        );
        assertReason("TREE_CONFLICT",
                () -> service.readEntries(new ByteArrayInputStream(treeConflict)));
    }

    @Test
    void rejectsFileDirectoryTreeConflicts() throws Exception {
        byte[] zip = buildZip(
                entry("train.py", "pass"),
                entry("train.py/config.json", "{}")
        );

        assertReason("TREE_CONFLICT", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsEncryptedGeneralPurposeBitBeforeReadingContent() throws Exception {
        byte[] zip = buildRawStoredZip(new RawEntry(
                "secret.py",
                "not-really-encrypted".getBytes(StandardCharsets.UTF_8),
                1,
                0
        ));
        zip[6] = 0;
        zip[7] = 0;

        assertReason("ZIP_ENCRYPTED", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsEncryptionBitPresentOnlyInLocalHeader() throws Exception {
        byte[] zip = buildRawStoredZip(rawEntry("secret.py", "content"));
        zip[6] = 1;
        zip[7] = 0;

        assertReason("ZIP_ENCRYPTED", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsStrongEncryptionBitPresentOnlyInCentralDirectory() throws Exception {
        byte[] zip = buildRawStoredZip(new RawEntry(
                "secret.py",
                "content".getBytes(StandardCharsets.UTF_8),
                0x0040,
                0
        ));
        zip[6] = 0;
        zip[7] = 0;

        assertReason("ZIP_ENCRYPTED", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsStrongEncryptionBitPresentOnlyInLocalHeader() throws Exception {
        byte[] zip = buildRawStoredZip(rawEntry("secret.py", "content"));
        zip[6] = 0x40;
        zip[7] = 0;

        assertReason("ZIP_ENCRYPTED", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsStrongEncryptionBitPresentInCentralAndLocalHeaders() throws Exception {
        byte[] zip = buildRawStoredZip(new RawEntry(
                "secret.py",
                "content".getBytes(StandardCharsets.UTF_8),
                0x0040,
                0
        ));

        assertReason("ZIP_ENCRYPTED", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsUnixSymlinkFromCentralDirectoryMetadata() throws Exception {
        byte[] zip = buildRawStoredZip(new RawEntry(
                "link.py",
                "target.py".getBytes(StandardCharsets.UTF_8),
                0,
                0120777
        ));

        assertReason("ZIP_SYMLINK", () -> service.readEntries(new ByteArrayInputStream(zip)));
    }

    @Test
    void rejectsTenThousandAndFirstEntry() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 10_001; i++) {
                zip.putNextEntry(new ZipEntry("entries/entry-" + i + ".txt"));
                zip.closeEntry();
            }
        }

        assertReason(
                "ZIP_TOO_MANY_ENTRIES",
                () -> service.readEntries(new ByteArrayInputStream(out.toByteArray()))
        );
    }

    @Test
    void countsActuallyStreamedBytesAgainstExpandedLimit() throws Exception {
        CodeZipArchiveService constrained = new CodeZipArchiveService(10_000, 7);
        byte[] zip = buildZip(entry("eight.txt", "12345678"));

        assertReason(
                "ZIP_EXPANDED_SIZE_EXCEEDED",
                () -> constrained.readEntries(new ByteArrayInputStream(zip))
        );
    }

    @Test
    void writesByteForByteDeterministicArchiveWithLexicalNames() throws Exception {
        Map<String, byte[]> firstInput = new LinkedHashMap<>();
        firstInput.put("z.txt", "last\r\n".getBytes(StandardCharsets.UTF_8));
        firstInput.put("src\\train.py", "print('ok')\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> secondInput = new LinkedHashMap<>();
        secondInput.put("src/train.py", "print('ok')\n".getBytes(StandardCharsets.UTF_8));
        secondInput.put("z.txt", "last\r\n".getBytes(StandardCharsets.UTF_8));

        byte[] first = service.writeDeterministic(firstInput);
        byte[] second = service.writeDeterministic(secondInput);

        assertArrayEquals(first, second);
        assertEquals(List.of("src/train.py", "z.txt"), zipNames(first));
        LinkedHashMap<String, byte[]> roundTrip = service.readEntries(new ByteArrayInputStream(first));
        assertArrayEquals(firstInput.get("z.txt"), roundTrip.get("z.txt"));
        assertArrayEquals(firstInput.get("src\\train.py"), roundTrip.get("src/train.py"));
    }

    private static void assertReason(String reasonCode, ThrowingAction action) {
        CodeValidationException error = assertThrows(CodeValidationException.class, action::run);
        assertEquals(reasonCode, error.getReasonCode());
    }

    private static void assertReason(
            String reasonCode,
            ThrowingAction action,
            String context
    ) {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                action::run,
                context
        );
        assertEquals(reasonCode, error.getReasonCode(), context);
    }

    private static String printable(String value) {
        return value.chars()
                .mapToObj(character -> character < 0x20 || character == 0x7f
                        ? String.format("\\u%04x", character)
                        : Character.toString(character))
                .collect(java.util.stream.Collectors.joining());
    }

    private static EntrySpec entry(String name, String content) {
        return new EntrySpec(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static RawEntry rawEntry(String name, String content) {
        return new RawEntry(name, content.getBytes(StandardCharsets.UTF_8), 0, 0);
    }

    private static byte[] buildZip(EntrySpec... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (EntrySpec spec : entries) {
                zip.putNextEntry(new ZipEntry(spec.name()));
                zip.write(spec.content());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static List<String> zipNames(byte[] zipBytes) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static byte[] buildRawStoredZip(RawEntry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(output);
        List<CentralEntry> centralEntries = new ArrayList<>();
        for (RawEntry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.content());
            int localOffset = output.size();
            writeIntLe(out, 0x04034b50);
            writeShortLe(out, 20);
            writeShortLe(out, entry.generalPurposeFlags());
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, crc.getValue());
            writeIntLe(out, entry.content().length);
            writeIntLe(out, entry.content().length);
            writeShortLe(out, name.length);
            writeShortLe(out, 0);
            out.write(name);
            out.write(entry.content());
            centralEntries.add(new CentralEntry(entry, name, crc.getValue(), localOffset));
        }

        int centralOffset = output.size();
        for (CentralEntry central : centralEntries) {
            RawEntry entry = central.entry();
            int madeBy = entry.unixMode() == 0 ? 20 : (3 << 8) | 20;
            writeIntLe(out, 0x02014b50);
            writeShortLe(out, madeBy);
            writeShortLe(out, 20);
            writeShortLe(out, entry.generalPurposeFlags());
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, central.crc());
            writeIntLe(out, entry.content().length);
            writeIntLe(out, entry.content().length);
            writeShortLe(out, central.name().length);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, ((long) entry.unixMode()) << 16);
            writeIntLe(out, central.localOffset());
            out.write(central.name());
        }
        int centralSize = output.size() - centralOffset;
        writeIntLe(out, 0x06054b50);
        writeShortLe(out, 0);
        writeShortLe(out, 0);
        writeShortLe(out, entries.length);
        writeShortLe(out, entries.length);
        writeIntLe(out, centralSize);
        writeIntLe(out, centralOffset);
        writeShortLe(out, 0);
        out.flush();
        return output.toByteArray();
    }

    private static void writeShortLe(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLe(DataOutputStream out, long value) throws Exception {
        out.writeByte((int) (value & 0xff));
        out.writeByte((int) ((value >>> 8) & 0xff));
        out.writeByte((int) ((value >>> 16) & 0xff));
        out.writeByte((int) ((value >>> 24) & 0xff));
    }

    private record EntrySpec(String name, byte[] content) {
    }

    private record RawEntry(String name, byte[] content, int generalPurposeFlags, int unixMode) {
    }

    private record CentralEntry(RawEntry entry, byte[] name, long crc, int localOffset) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
