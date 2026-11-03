package com.tss.platform.service;

/** Verified metadata for one effective workspace file, including large files. */
public record CodeWorkspaceFileMetadata(
        CodeFileDescriptor descriptor,
        String contentHash,
        long workspaceRevision,
        boolean readOnly
) {
}
