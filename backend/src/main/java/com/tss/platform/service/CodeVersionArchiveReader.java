package com.tss.platform.service;

import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads validated code ZIP metadata and individual entries by byte range.
 * Internal object names never leave this boundary.
 */
@Service
public class CodeVersionArchiveReader {

    static final int MAX_CODE_ENTRIES = 10_000;
    static final long MAX_CODE_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    private final ZipCentralDirectoryReader centralDirectoryReader;
    private final MinioService minioService;
    private final AuthContext authContext;
    private final CodePathPolicy pathPolicy;
    private final CodeFilePolicy filePolicy;

    public CodeVersionArchiveReader(
            ZipCentralDirectoryReader centralDirectoryReader,
            MinioService minioService,
            AuthContext authContext,
            CodePathPolicy pathPolicy,
            CodeFilePolicy filePolicy
    ) {
        this.centralDirectoryReader = centralDirectoryReader;
        this.minioService = minioService;
        this.authContext = authContext;
        this.pathPolicy = pathPolicy;
        this.filePolicy = filePolicy;
    }

    public List<CodeArchiveEntry> list(CodeVersion version, Integer assetOwnerId) {
        String objectName = authorize(version, assetOwnerId);
        if (version.getSizeBytes() == null || version.getSizeBytes() < 22) {
            throw inaccessible();
        }
        try {
            List<ZipEntryInfo> zipEntries = centralDirectoryReader.read(
                    objectName,
                    version.getSizeBytes(),
                    MAX_CODE_ENTRIES
            );
            List<CodeArchiveEntry> files = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            long totalUncompressedBytes = 0;
            for (ZipEntryInfo zipEntry : zipEntries) {
                if (zipEntry.directory()) {
                    pathPolicy.normalizeRawArchiveDirectoryPath(zipEntry.path());
                    continue;
                }
                if (zipEntry.encrypted() || (zipEntry.method() != 0 && zipEntry.method() != 8)) {
                    throw inaccessible();
                }
                String path = pathPolicy.normalizeRawArchiveFilePath(zipEntry.path());
                filePolicy.validateSupportedPath(path);
                if (zipEntry.compressedSize() < 0 || zipEntry.uncompressedSize() < 0) {
                    throw inaccessible();
                }
                if (totalUncompressedBytes
                        > MAX_CODE_UNCOMPRESSED_BYTES - zipEntry.uncompressedSize()) {
                    throw inaccessible();
                }
                totalUncompressedBytes += zipEntry.uncompressedSize();
                paths.add(path);
                files.add(new CodeArchiveEntry(
                        path,
                        zipEntry.method(),
                        zipEntry.compressedSize(),
                        zipEntry.uncompressedSize(),
                        zipEntry.crc32(),
                        zipEntry.zipDataOffset()
                ));
            }
            pathPolicy.validateNoTreeConflicts(paths);
            files.sort(Comparator.comparing(CodeArchiveEntry::path));
            return List.copyOf(files);
        } catch (CodeAssetAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw inaccessible();
        }
    }

    public byte[] read(
            CodeVersion version,
            Integer assetOwnerId,
            CodeArchiveEntry entry,
            long outputLimit
    ) {
        String objectName = authorize(version, assetOwnerId);
        if (entry == null
                || outputLimit < 0
                || entry.uncompressedSize() < 0
                || entry.compressedSize() < 0
                || entry.zipDataOffset() < 0
                || (entry.method() != 0 && entry.method() != 8)) {
            throw inaccessible();
        }
        if (version.getSizeBytes() == null
                || !rangeWithin(
                        entry.zipDataOffset(),
                        entry.compressedSize(),
                        version.getSizeBytes()
                )) {
            throw inaccessible();
        }
        if (entry.uncompressedSize() > outputLimit) {
            throw new CodeContentTooLargeException();
        }
        if (entry.uncompressedSize() > Integer.MAX_VALUE) {
            throw new CodeContentTooLargeException();
        }
        if (entry.compressedSize() == 0) {
            if (entry.uncompressedSize() == 0 && entry.crc32() == 0) {
                return new byte[0];
            }
            throw inaccessible();
        }

        try (InputStream range = minioService.downloadRange(
                objectName,
                entry.zipDataOffset(),
                entry.compressedSize()
        )) {
            InputStream bounded = new RangeInputStream(range, entry.compressedSize());
            byte[] raw;
            if (entry.method() == 0) {
                if (entry.compressedSize() != entry.uncompressedSize()) {
                    throw inaccessible();
                }
                raw = readBoundedOutput(bounded, entry.uncompressedSize(), outputLimit);
            } else {
                Inflater inflater = new Inflater(true);
                try {
                    InflaterInputStream inflated = new InflaterInputStream(bounded, inflater, 8192);
                    raw = readBoundedOutput(inflated, entry.uncompressedSize(), outputLimit);
                } finally {
                    inflater.end();
                }
            }
            verifyCrc(raw, entry.crc32());
            return raw.clone();
        } catch (CodeContentTooLargeException | CodeAssetAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw inaccessible();
        }
    }

    /**
     * Computes a raw-content SHA-256 for one validated entry without retaining
     * the entry body in memory. Declared length, object range and CRC are checked
     * while streaming, so the result is suitable for workspace content-hash CAS.
     */
    public String sha256(
            CodeVersion version,
            Integer assetOwnerId,
            CodeArchiveEntry entry
    ) {
        String objectName = authorize(version, assetOwnerId);
        if (entry == null
                || entry.uncompressedSize() < 0
                || entry.compressedSize() < 0
                || entry.zipDataOffset() < 0
                || entry.uncompressedSize() > MAX_CODE_UNCOMPRESSED_BYTES
                || (entry.method() != 0 && entry.method() != 8)) {
            throw inaccessible();
        }
        if (version.getSizeBytes() == null
                || !rangeWithin(
                        entry.zipDataOffset(),
                        entry.compressedSize(),
                        version.getSizeBytes()
                )) {
            throw inaccessible();
        }
        if (entry.compressedSize() == 0) {
            if (entry.uncompressedSize() == 0 && entry.crc32() == 0) {
                return sha256Hex(new byte[0]);
            }
            throw inaccessible();
        }

        try (InputStream range = minioService.downloadRange(
                objectName,
                entry.zipDataOffset(),
                entry.compressedSize()
        )) {
            InputStream bounded = new RangeInputStream(range, entry.compressedSize());
            HashResult result;
            if (entry.method() == 0) {
                if (entry.compressedSize() != entry.uncompressedSize()) {
                    throw inaccessible();
                }
                result = hashBoundedOutput(bounded, entry.uncompressedSize());
            } else {
                Inflater inflater = new Inflater(true);
                try {
                    InflaterInputStream inflated = new InflaterInputStream(bounded, inflater, 8192);
                    result = hashBoundedOutput(inflated, entry.uncompressedSize());
                } finally {
                    inflater.end();
                }
            }
            if (result.crc32() != entry.crc32()) {
                throw inaccessible();
            }
            return result.sha256();
        } catch (CodeAssetAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw inaccessible();
        }
    }

    private String authorize(CodeVersion version, Integer assetOwnerId) {
        if (version == null
                || assetOwnerId == null
                || !assetOwnerId.equals(version.getOwnerUserId())
                || version.getStoragePath() == null
                || version.getStoragePath().isBlank()) {
            throw inaccessible();
        }
        boolean allowed;
        try {
            allowed = authContext.canAccessObjectName(version.getStoragePath(), assetOwnerId);
        } catch (RuntimeException exception) {
            throw inaccessible();
        }
        if (!allowed) {
            throw inaccessible();
        }
        return version.getStoragePath();
    }

    private static byte[] readBoundedOutput(
            InputStream input,
            long expectedSize,
            long outputLimit
    ) throws IOException {
        int initialCapacity = (int) Math.min(expectedSize, 8192);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (count > outputLimit - read || count > expectedSize - read) {
                throw new IOException("Code archive output exceeds its bound");
            }
            output.write(buffer, 0, read);
            count += read;
        }
        if (count != expectedSize) {
            throw new IOException("Code archive entry length mismatch");
        }
        return output.toByteArray();
    }

    private static void verifyCrc(byte[] raw, long expectedCrc) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(raw);
        if (crc.getValue() != expectedCrc) {
            throw new IOException("Code archive entry checksum mismatch");
        }
    }

    private static HashResult hashBoundedOutput(
            InputStream input,
            long expectedSize
    ) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (count > expectedSize - read) {
                throw new IOException("Code archive output exceeds its declared length");
            }
            digest.update(buffer, 0, read);
            crc.update(buffer, 0, read);
            count += read;
        }
        if (count != expectedSize) {
            throw new IOException("Code archive entry length mismatch");
        }
        return new HashResult(HexFormat.of().formatHex(digest.digest()), crc.getValue());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CodeAssetAccessException inaccessible() {
        return new CodeAssetAccessException();
    }

    private static boolean rangeWithin(long offset, long length, long objectSize) {
        return offset >= 0
                && length >= 0
                && objectSize >= 0
                && offset <= objectSize
                && length <= objectSize - offset;
    }

    private static final class RangeInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private RangeInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = delegate.read();
            if (value < 0) {
                throw new IOException("Code archive range ended early");
            }
            remaining -= 1;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = delegate.read(bytes, offset, requested);
            if (read < 0) {
                throw new IOException("Code archive range ended early");
            }
            remaining -= read;
            return read;
        }
    }

    private record HashResult(String sha256, long crc32) {
    }
}
