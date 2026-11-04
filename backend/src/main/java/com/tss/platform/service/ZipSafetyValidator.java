package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;

import java.util.List;

final class ZipSafetyValidator {

    static final int MAX_ENTRIES = 100_000;
    static final long MAX_UNCOMPRESSED_SIZE = 50L * 1024 * 1024 * 1024;
    static final long MAX_COMPRESSION_RATIO = 1_000;

    private ZipSafetyValidator() {
    }

    static void validate(List<ZipEntryInfo> entries) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("ZIP entry count exceeds 100000");
        }

        long totalUncompressedSize = 0;
        for (ZipEntryInfo entry : entries) {
            if (entry.uncompressedSize() > MAX_UNCOMPRESSED_SIZE) {
                throw new IllegalArgumentException("ZIP entry exceeds 50GB: " + entry.path());
            }
            if (!entry.directory() && entry.compressedSize() == 0 && entry.uncompressedSize() > 0) {
                throw new IllegalArgumentException("ZIP entry has invalid zero compressed size: " + entry.path());
            }
            if (entry.compressedSize() > 0
                    && entry.uncompressedSize() > entry.compressedSize() * MAX_COMPRESSION_RATIO) {
                throw new IllegalArgumentException("ZIP entry compression ratio exceeds 1000: " + entry.path());
            }
            if (entry.method() == 0 && entry.compressedSize() != entry.uncompressedSize()) {
                throw new IllegalArgumentException("STORED ZIP entry has inconsistent sizes: " + entry.path());
            }
            if (totalUncompressedSize > MAX_UNCOMPRESSED_SIZE - entry.uncompressedSize()) {
                throw new IllegalArgumentException("ZIP total uncompressed size exceeds 50GB");
            }
            totalUncompressedSize += entry.uncompressedSize();
        }
    }
}
