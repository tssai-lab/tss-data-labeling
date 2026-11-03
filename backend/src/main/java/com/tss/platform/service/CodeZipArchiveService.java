package com.tss.platform.service;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Reads fully validated code ZIPs and emits canonical, byte-stable archives.
 */
@Component
public final class CodeZipArchiveService {

    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_EXPANDED_BYTES = 512L * 1024 * 1024;

    private static final long FIXED_ZIP_TIMESTAMP_MILLIS = 315_532_800_000L;
    private static final int ZIP_ENCRYPTION_FLAGS = 0x0041;

    private final int maxEntries;
    private final long maxExpandedBytes;
    private final CodePathPolicy pathPolicy;
    private final CodeFilePolicy filePolicy;

    public CodeZipArchiveService() {
        this(MAX_ENTRIES, MAX_EXPANDED_BYTES);
    }

    CodeZipArchiveService(int maxEntries, long maxExpandedBytes) {
        if (maxEntries <= 0 || maxExpandedBytes < 0) {
            throw new IllegalArgumentException("Archive limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxExpandedBytes = maxExpandedBytes;
        this.pathPolicy = new CodePathPolicy();
        this.filePolicy = new CodeFilePolicy();
    }

    public LinkedHashMap<String, byte[]> readEntries(InputStream zipInput) {
        if (zipInput == null) {
            throw validation("INVALID_ZIP", "Code archive input is required");
        }
        Path temporaryArchive = null;
        try {
            temporaryArchive = Files.createTempFile("code-archive-", ".zip");
            Files.copy(zipInput, temporaryArchive, StandardCopyOption.REPLACE_EXISTING);
            try (ZipFile zip = ZipFile.builder()
                    .setPath(temporaryArchive)
                    .setCharset(StandardCharsets.UTF_8)
                    .setUseUnicodeExtraFields(true)
                    .get();
                 SeekableByteChannel localHeaders = Files.newByteChannel(
                         temporaryArchive,
                         StandardOpenOption.READ
                 )) {
                return readZipFile(zip, localHeaders);
            }
        } catch (CodeValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CodeValidationException("INVALID_ZIP", "Code archive could not be read", e);
        } finally {
            deleteTemporaryArchive(temporaryArchive);
        }
    }

    public byte[] writeDeterministic(Map<String, byte[]> files) {
        TreeMap<String, byte[]> normalizedFiles = normalizeOutputFiles(files);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            configureDeterministicOutput(zip);
            for (Map.Entry<String, byte[]> file : normalizedFiles.entrySet()) {
                byte[] content = file.getValue();
                CRC32 crc = new CRC32();
                crc.update(content);
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(crc.getValue());
                entry.setTime(FIXED_ZIP_TIMESTAMP_MILLIS);
                entry.setInternalAttributes(0);
                entry.setExternalAttributes(0);
                zip.putArchiveEntry(entry);
                zip.write(content);
                zip.closeArchiveEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new CodeValidationException("ZIP_WRITE_FAILED", "Code archive could not be created", e);
        }
    }

    private LinkedHashMap<String, byte[]> readZipFile(
            ZipFile zip,
            SeekableByteChannel localHeaders
    ) throws IOException {
        List<String> filePaths = new ArrayList<>();
        Set<String> directoryPaths = new HashSet<>();
        Map<String, byte[]> files = new TreeMap<>();
        long expandedBytes = 0;
        int entries = 0;
        Enumeration<ZipArchiveEntry> archiveEntries = zip.getEntriesInPhysicalOrder();
        while (archiveEntries.hasMoreElements()) {
            ZipArchiveEntry entry = archiveEntries.nextElement();
            entries += 1;
            if (entries > maxEntries) {
                throw validation("ZIP_TOO_MANY_ENTRIES", "Code archive has too many entries");
            }
            if ((entry.getRawFlag() & ZIP_ENCRYPTION_FLAGS) != 0
                    || localHeaderUsesEncryption(localHeaders, entry)) {
                throw validation("ZIP_ENCRYPTED", "Encrypted code archives are not supported");
            }
            if (entry.isUnixSymlink()) {
                throw validation("ZIP_SYMLINK", "Symbolic links are not allowed in code archives");
            }
            String rawEntryName = decodeRawEntryName(entry);
            if (entry.isDirectory() || rawEntryName.endsWith("\\")) {
                String directoryPath = pathPolicy.normalizeRawArchiveDirectoryPath(
                        rawEntryName
                );
                if (!directoryPaths.add(directoryPath)) {
                    throw validation("DUPLICATE_PATH", "Code archive contains duplicate paths");
                }
                continue;
            }

            String normalizedPath = pathPolicy.normalizeRawArchiveFilePath(rawEntryName);
            filePolicy.validateSupportedPath(normalizedPath);
            if (files.containsKey(normalizedPath)) {
                throw validation("DUPLICATE_PATH", "Code archive contains duplicate paths");
            }
            byte[] content;
            try (InputStream input = zip.getInputStream(entry)) {
                EntryContent read = readEntry(input, expandedBytes);
                content = read.bytes();
                expandedBytes = read.totalExpandedBytes();
            }
            filePolicy.validateUtf8(content);
            filePaths.add(normalizedPath);
            files.put(normalizedPath, content);
        }
        if (filePaths.isEmpty()) {
            throw validation("ZIP_EMPTY", "Code archive contains no files");
        }
        pathPolicy.validateNoTreeConflicts(filePaths);
        validateDirectoryConflicts(directoryPaths, filePaths);
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        files.forEach(result::put);
        return result;
    }

    private EntryContent readEntry(InputStream input, long currentExpandedBytes) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = currentExpandedBytes;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxExpandedBytes - total) {
                throw validation(
                        "ZIP_EXPANDED_SIZE_EXCEEDED",
                        "Code archive exceeds the expanded content limit"
                );
            }
            total += read;
            content.write(buffer, 0, read);
        }
        return new EntryContent(content.toByteArray(), total);
    }

    private static String decodeRawEntryName(ZipArchiveEntry entry) {
        byte[] rawName = entry.getRawName();
        if (rawName == null || rawName.length == 0) {
            throw validation("INVALID_PATH", "Code file path is invalid");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw validation("INVALID_PATH", "Code file path is invalid");
        }
    }

    private static boolean localHeaderUsesEncryption(
            SeekableByteChannel channel,
            ZipArchiveEntry entry
    ) throws IOException {
        long offset = entry.getLocalHeaderOffset();
        if (offset < 0) {
            throw new IOException("ZIP local header offset is unavailable");
        }
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (header.hasRemaining()) {
            if (channel.read(header) < 0) {
                throw new IOException("ZIP local header is truncated");
            }
        }
        header.flip();
        if (header.getInt() != 0x04034b50) {
            throw new IOException("ZIP local header signature is invalid");
        }
        header.getShort();
        return (Short.toUnsignedInt(header.getShort()) & ZIP_ENCRYPTION_FLAGS) != 0;
    }

    private TreeMap<String, byte[]> normalizeOutputFiles(Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) {
            throw validation("ZIP_EMPTY", "Code archive contains no files");
        }
        if (files.size() > maxEntries) {
            throw validation("ZIP_TOO_MANY_ENTRIES", "Code archive has too many entries");
        }
        TreeMap<String, byte[]> normalized = new TreeMap<>(Comparator.naturalOrder());
        long expandedBytes = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = pathPolicy.normalizeFilePath(file.getKey());
            filePolicy.validateSupportedPath(path);
            byte[] content = file.getValue();
            if (content == null) {
                throw validation("INVALID_CONTENT", "Code file content is required");
            }
            filePolicy.validateUtf8(content);
            if (content.length > maxExpandedBytes - expandedBytes) {
                throw validation(
                        "ZIP_EXPANDED_SIZE_EXCEEDED",
                        "Code archive exceeds the expanded content limit"
                );
            }
            expandedBytes += content.length;
            if (normalized.putIfAbsent(path, content) != null) {
                throw validation("DUPLICATE_PATH", "Code archive contains duplicate paths");
            }
        }
        pathPolicy.validateNoTreeConflicts(normalized.keySet());
        return normalized;
    }

    private static void configureDeterministicOutput(ZipArchiveOutputStream zip) {
        zip.setEncoding(StandardCharsets.UTF_8.name());
        zip.setUseLanguageEncodingFlag(true);
        zip.setFallbackToUTF8(true);
        zip.setCreateUnicodeExtraFields(UnicodeExtraFieldPolicy.NEVER);
        zip.setUseZip64(Zip64Mode.Never);
    }

    private static void validateDirectoryConflicts(Collection<String> directories, Collection<String> files) {
        for (String directory : directories) {
            for (String file : files) {
                if (directory.equals(file) || directory.startsWith(file + "/")) {
                    throw validation("TREE_CONFLICT", "Code archive contains a file-directory conflict");
                }
            }
        }
    }

    private static void deleteTemporaryArchive(Path temporaryArchive) {
        if (temporaryArchive == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryArchive);
        } catch (IOException ignored) {
            temporaryArchive.toFile().deleteOnExit();
        }
    }

    private static CodeValidationException validation(String reasonCode, String message) {
        return new CodeValidationException(reasonCode, message);
    }

    private record EntryContent(byte[] bytes, long totalExpandedBytes) {
    }
}
