package com.tss.platform.dto.v2;

import com.tss.platform.service.CodeWorkspaceFileMetadata;

/** Storage-neutral file metadata used for preview decisions and delete CAS. */
public record V2CodeFileMetadata(
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
        String contentHash,
        long workspaceRevision,
        boolean readOnly,
        boolean deletable
) {

    public static V2CodeFileMetadata from(
            CodeWorkspaceFileMetadata source,
            boolean workspaceReadOnly
    ) {
        var descriptor = source.descriptor();
        boolean readOnly = workspaceReadOnly || source.readOnly();
        return new V2CodeFileMetadata(
                descriptor.path(),
                descriptor.name(),
                descriptor.nodeType(),
                descriptor.extension(),
                descriptor.languageId(),
                descriptor.contentType(),
                descriptor.sizeBytes(),
                descriptor.previewable(),
                readOnly ? false : descriptor.editable(),
                descriptor.downloadable(),
                descriptor.reasonCode(),
                source.contentHash(),
                source.workspaceRevision(),
                readOnly,
                !readOnly
        );
    }
}
