package com.tss.platform.service;

/** Frontend-neutral direct-child node in a code workspace tree. */
public record CodeWorkspaceTreeNode(
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

    static CodeWorkspaceTreeNode directory(String path, String name) {
        return new CodeWorkspaceTreeNode(
                path,
                name,
                "DIRECTORY",
                null,
                null,
                null,
                0,
                false,
                false,
                false,
                null,
                null
        );
    }

    static CodeWorkspaceTreeNode file(CodeFileDescriptor descriptor) {
        return new CodeWorkspaceTreeNode(
                descriptor.path(),
                descriptor.name(),
                descriptor.nodeType(),
                descriptor.extension(),
                descriptor.languageId(),
                descriptor.contentType(),
                descriptor.sizeBytes(),
                descriptor.previewable(),
                descriptor.editable(),
                descriptor.downloadable(),
                descriptor.reasonCode(),
                descriptor.contentHash()
        );
    }
}
