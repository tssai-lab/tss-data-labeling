package com.tss.platform.dto.v2;

import com.tss.platform.service.CodeFileDescriptor;

/** Public UTF-8 preview projection. Raw bytes never enter JSON serialization. */
public record V2CodeFileContent(
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
        String content,
        String charset,
        String contentHash,
        Long workspaceRevision,
        boolean readOnly
) {

    public static V2CodeFileContent readOnly(
            CodeFileDescriptor descriptor,
            String content
    ) {
        return new V2CodeFileContent(
                descriptor.path(),
                descriptor.name(),
                descriptor.nodeType(),
                descriptor.extension(),
                descriptor.languageId(),
                descriptor.contentType(),
                descriptor.sizeBytes(),
                descriptor.previewable(),
                false,
                descriptor.downloadable(),
                descriptor.reasonCode(),
                content,
                "UTF-8",
                descriptor.contentHash(),
                null,
                true
        );
    }
}
