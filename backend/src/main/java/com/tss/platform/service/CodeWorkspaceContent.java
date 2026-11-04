package com.tss.platform.service;

import java.util.Arrays;

/** Exact workspace file content plus concurrency metadata. */
public record CodeWorkspaceContent(
        CodeFileDescriptor descriptor,
        String content,
        String charset,
        String contentHash,
        long workspaceRevision,
        boolean readOnly,
        byte[] rawBytes
) {

    public CodeWorkspaceContent {
        rawBytes = rawBytes == null ? null : Arrays.copyOf(rawBytes, rawBytes.length);
    }

    @Override
    public byte[] rawBytes() {
        return rawBytes == null ? null : Arrays.copyOf(rawBytes, rawBytes.length);
    }
}
