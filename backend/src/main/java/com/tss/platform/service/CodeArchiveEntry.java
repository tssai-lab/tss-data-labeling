package com.tss.platform.service;

/** Safe, storage-neutral range metadata for one validated code archive file. */
public record CodeArchiveEntry(
        String path,
        int method,
        long compressedSize,
        long uncompressedSize,
        long crc32,
        long zipDataOffset
) {
}
