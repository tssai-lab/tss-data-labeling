package com.tss.platform.model;

public record ZipEntryInfo(
        String path,
        String normalizedPath,
        int method,
        long compressedSize,
        long uncompressedSize,
        long crc32,
        long localHeaderOffset,
        long zipDataOffset,
        boolean encrypted,
        boolean directory
) {
}
