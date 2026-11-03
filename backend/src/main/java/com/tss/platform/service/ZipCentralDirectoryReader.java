package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ZipCentralDirectoryReader {

    private static final long MAX_CENTRAL_DIRECTORY_SIZE = 128L * 1024 * 1024;
    private static final int[] EOCD_WINDOWS = {
            128 * 1024,
            256 * 1024,
            512 * 1024,
            1024 * 1024,
            2 * 1024 * 1024
    };
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final Charset ZIP_LEGACY_CHARSET = Charset.forName("IBM437");

    private final MinioService minioService;

    public ZipCentralDirectoryReader(MinioService minioService) {
        this.minioService = minioService;
    }

    public List<ZipEntryInfo> read(String objectName, long objectSize) throws Exception {
        return read(objectName, objectSize, ZipSafetyValidator.MAX_ENTRIES);
    }

    public List<ZipEntryInfo> read(
            String objectName,
            long objectSize,
            int maxEntries
    ) throws Exception {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName cannot be blank");
        }
        if (objectSize < 22) {
            throw new IllegalArgumentException("ZIP object is too small");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be greater than zero");
        }

        Eocd eocd = findEocd(objectName, objectSize);
        validateEocd(eocd, objectSize);
        if (eocd.entryCount() > maxEntries) {
            throw new IllegalArgumentException(
                    "ZIP entry count exceeds " + maxEntries
            );
        }
        if (eocd.entryCount() == 0) {
            return List.of();
        }

        byte[] centralDirectory = readExactly(
                objectName,
                eocd.centralDirectoryOffset(),
                eocd.centralDirectorySize()
        );
        List<CentralEntry> centralEntries = parseCentralDirectory(centralDirectory, eocd.entryCount());
        List<ZipEntryInfo> entries = new ArrayList<>(centralEntries.size());
        Set<String> normalizedPaths = new HashSet<>();

        for (CentralEntry centralEntry : centralEntries) {
            String normalizedPath = normalizePath(centralEntry.path());
            if (!normalizedPaths.add(normalizedPath)) {
                throw new IllegalArgumentException("ZIP contains duplicate normalized path: " + normalizedPath);
            }
            long zipDataOffset = readZipDataOffset(objectName, objectSize, centralEntry);
            validateObjectBounds(centralEntry, zipDataOffset, objectSize);
            entries.add(new ZipEntryInfo(
                    centralEntry.path(),
                    normalizedPath,
                    centralEntry.method(),
                    centralEntry.compressedSize(),
                    centralEntry.uncompressedSize(),
                    centralEntry.crc32(),
                    centralEntry.localHeaderOffset(),
                    zipDataOffset,
                    centralEntry.encrypted(),
                    centralEntry.directory()
            ));
        }

        ZipSafetyValidator.validate(entries);
        return List.copyOf(entries);
    }

    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("ZIP entry path cannot be empty");
        }
        String normalizedPath = path.replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP entry path cannot be absolute: " + path);
        }
        if (normalizedPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ZIP entry path cannot contain null bytes");
        }

        boolean directory = normalizedPath.endsWith("/");
        List<String> parts = new ArrayList<>();
        for (String part : normalizedPath.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("ZIP entry path cannot contain '..': " + path);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("ZIP entry path cannot normalize to empty: " + path);
        }
        String normalized = String.join("/", parts);
        return directory ? normalized + "/" : normalized;
    }

    private Eocd findEocd(String objectName, long objectSize) throws Exception {
        long previousWindow = -1;
        for (int requestedWindow : EOCD_WINDOWS) {
            long window = Math.min(objectSize, requestedWindow);
            if (window == previousWindow) {
                continue;
            }
            previousWindow = window;
            long offset = objectSize - window;
            byte[] tail = readExactly(objectName, offset, window);
            int eocdIndex = findEocdIndex(tail);
            if (eocdIndex >= 0) {
                if (eocdIndex >= 20 && u32(tail, eocdIndex - 20) == ZIP64_EOCD_LOCATOR_SIGNATURE) {
                    throw new IllegalArgumentException("ZIP64 is not supported in C1");
                }
                return parseEocd(tail, eocdIndex);
            }
        }
        throw new IllegalArgumentException("ZIP end of central directory was not found");
    }

    private static int findEocdIndex(byte[] tail) {
        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) != EOCD_SIGNATURE) {
                continue;
            }
            int commentLength = u16(tail, i + 20);
            if (i + 22 + commentLength == tail.length) {
                return i;
            }
        }
        return -1;
    }

    private static Eocd parseEocd(byte[] tail, int offset) {
        int diskNumber = u16(tail, offset + 4);
        int centralDirectoryDisk = u16(tail, offset + 6);
        int entriesOnDisk = u16(tail, offset + 8);
        int entryCount = u16(tail, offset + 10);
        long centralDirectorySize = u32(tail, offset + 12);
        long centralDirectoryOffset = u32(tail, offset + 16);

        if (entryCount == 0xffff
                || entriesOnDisk == 0xffff
                || centralDirectorySize == 0xffffffffL
                || centralDirectoryOffset == 0xffffffffL) {
            throw new IllegalArgumentException("ZIP64 is not supported in C1");
        }
        if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != entryCount) {
            throw new IllegalArgumentException("Multi-volume ZIP archives are not supported");
        }
        return new Eocd(entryCount, centralDirectoryOffset, centralDirectorySize);
    }

    private static void validateEocd(Eocd eocd, long objectSize) {
        if (eocd.entryCount() > ZipSafetyValidator.MAX_ENTRIES) {
            throw new IllegalArgumentException("ZIP entry count exceeds 100000");
        }
        if (eocd.centralDirectorySize() > MAX_CENTRAL_DIRECTORY_SIZE) {
            throw new IllegalArgumentException("ZIP central directory exceeds 128MB");
        }
        if (!rangeWithin(eocd.centralDirectoryOffset(), eocd.centralDirectorySize(), objectSize)) {
            throw new IllegalArgumentException("ZIP central directory is outside the object");
        }
        if (eocd.entryCount() > 0 && eocd.centralDirectorySize() == 0) {
            throw new IllegalArgumentException("ZIP central directory is empty");
        }
    }

    private static List<CentralEntry> parseCentralDirectory(byte[] bytes, int expectedEntryCount) {
        List<CentralEntry> entries = new ArrayList<>(expectedEntryCount);
        int cursor = 0;
        for (int index = 0; index < expectedEntryCount; index++) {
            requireAvailable(bytes, cursor, 46, "truncated central directory header");
            if (u32(bytes, cursor) != CENTRAL_HEADER_SIGNATURE) {
                throw new IllegalArgumentException("Invalid ZIP central directory header");
            }

            int flags = u16(bytes, cursor + 8);
            int method = u16(bytes, cursor + 10);
            long crc32 = u32(bytes, cursor + 16);
            long compressedSize = u32(bytes, cursor + 20);
            long uncompressedSize = u32(bytes, cursor + 24);
            int fileNameLength = u16(bytes, cursor + 28);
            int extraLength = u16(bytes, cursor + 30);
            int commentLength = u16(bytes, cursor + 32);
            int diskStart = u16(bytes, cursor + 34);
            long localHeaderOffset = u32(bytes, cursor + 42);
            int recordLength = 46 + fileNameLength + extraLength + commentLength;
            requireAvailable(bytes, cursor, recordLength, "truncated central directory entry");

            if (diskStart != 0) {
                throw new IllegalArgumentException("Multi-volume ZIP archives are not supported");
            }
            if (compressedSize == 0xffffffffL
                    || uncompressedSize == 0xffffffffL
                    || localHeaderOffset == 0xffffffffL
                    || containsZip64Extra(bytes, cursor + 46 + fileNameLength, extraLength)) {
                throw new IllegalArgumentException("ZIP64 is not supported in C1");
            }
            boolean encrypted = (flags & 0x0001) != 0 || (flags & 0x0040) != 0;
            if (encrypted) {
                throw new IllegalArgumentException("Encrypted ZIP entries are not supported");
            }
            if (method != 0 && method != 8) {
                throw new IllegalArgumentException("Unsupported ZIP compression method: " + method);
            }

            Charset charset = (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : ZIP_LEGACY_CHARSET;
            String path = new String(bytes, cursor + 46, fileNameLength, charset);
            entries.add(new CentralEntry(
                    path,
                    method,
                    compressedSize,
                    uncompressedSize,
                    crc32,
                    localHeaderOffset,
                    encrypted,
                    path.endsWith("/") || path.endsWith("\\")
            ));
            cursor += recordLength;
        }
        return entries;
    }

    private long readZipDataOffset(String objectName, long objectSize, CentralEntry entry) throws Exception {
        if (!rangeWithin(entry.localHeaderOffset(), 30, objectSize)) {
            throw new IllegalArgumentException("ZIP local header is outside the object: " + entry.path());
        }
        byte[] localHeader = readExactly(objectName, entry.localHeaderOffset(), 30);
        if (u32(localHeader, 0) != LOCAL_HEADER_SIGNATURE) {
            throw new IllegalArgumentException("Invalid ZIP local header: " + entry.path());
        }
        int localFlags = u16(localHeader, 6);
        int localMethod = u16(localHeader, 8);
        if ((localFlags & 0x0001) != 0 || (localFlags & 0x0040) != 0) {
            throw new IllegalArgumentException("Encrypted ZIP entries are not supported");
        }
        if (localMethod != entry.method()) {
            throw new IllegalArgumentException("ZIP compression method differs between headers: " + entry.path());
        }
        int fileNameLength = u16(localHeader, 26);
        int extraLength = u16(localHeader, 28);
        long dataOffset = entry.localHeaderOffset() + 30L + fileNameLength + extraLength;
        if (!rangeWithin(entry.localHeaderOffset(), 30L + fileNameLength + extraLength, objectSize)) {
            throw new IllegalArgumentException("ZIP local header fields exceed the object: " + entry.path());
        }
        return dataOffset;
    }

    private static void validateObjectBounds(CentralEntry entry, long zipDataOffset, long objectSize) {
        if (!rangeWithin(entry.localHeaderOffset(), 30, objectSize)
                || !rangeWithin(zipDataOffset, entry.compressedSize(), objectSize)) {
            throw new IllegalArgumentException("ZIP entry data exceeds the object: " + entry.path());
        }
    }

    private byte[] readExactly(String objectName, long offset, long length) throws Exception {
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested ZIP range is too large");
        }
        try (InputStream input = minioService.downloadRange(objectName, offset, length);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) length)) {
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IllegalArgumentException("MinIO range response ended early");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        }
    }

    private static boolean containsZip64Extra(byte[] bytes, int offset, int length) {
        int cursor = offset;
        int end = offset + length;
        while (cursor + 4 <= end) {
            int headerId = u16(bytes, cursor);
            int dataSize = u16(bytes, cursor + 2);
            if (cursor + 4 + dataSize > end) {
                throw new IllegalArgumentException("Invalid ZIP extra field");
            }
            if (headerId == 0x0001) {
                return true;
            }
            cursor += 4 + dataSize;
        }
        if (cursor != end) {
            throw new IllegalArgumentException("Invalid ZIP extra field");
        }
        return false;
    }

    private static void requireAvailable(byte[] bytes, int offset, int length, String message) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean rangeWithin(long offset, long length, long objectSize) {
        return offset >= 0 && length >= 0 && offset <= objectSize && length <= objectSize - offset;
    }

    private static int u16(byte[] bytes, int offset) {
        requireAvailable(bytes, offset, 2, "truncated ZIP field");
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        requireAvailable(bytes, offset, 4, "truncated ZIP field");
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private record Eocd(int entryCount, long centralDirectoryOffset, long centralDirectorySize) {
    }

    private record CentralEntry(
            String path,
            int method,
            long compressedSize,
            long uncompressedSize,
            long crc32,
            long localHeaderOffset,
            boolean encrypted,
            boolean directory
    ) {
    }
}
