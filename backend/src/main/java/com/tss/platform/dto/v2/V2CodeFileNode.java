package com.tss.platform.dto.v2;

import com.tss.platform.service.CodeFileDescriptor;

/** Public tree node. Content hashes are deliberately absent from tree responses. */
public record V2CodeFileNode(
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
        String reasonCode
) {

    public static V2CodeFileNode file(CodeFileDescriptor descriptor, boolean readOnly) {
        return new V2CodeFileNode(
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
                descriptor.reasonCode()
        );
    }

    public static V2CodeFileNode directory(String path, String name) {
        return new V2CodeFileNode(
                path, name, "DIRECTORY", null, null, null, 0,
                false, false, false, null
        );
    }
}
