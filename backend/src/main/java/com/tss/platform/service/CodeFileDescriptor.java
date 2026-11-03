package com.tss.platform.service;

/**
 * Frontend-neutral metadata for one code asset file.
 */
public record CodeFileDescriptor(
        String path,
        String name,
        String nodeType,
        String extension,
        String languageId,
        String contentType,
        long sizeBytes,
        boolean previewable,
        boolean editable,
        boolean downloadable,
        String reasonCode,
        String contentHash
) {
}
