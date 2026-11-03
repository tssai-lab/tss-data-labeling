package com.tss.platform.dto.v2;

/** Atomic file move request. */
public record V2CodeFileMoveRequest(
        String sourcePath,
        String targetPath,
        Long expectedWorkspaceRevision,
        String expectedContentHash
) {
}
