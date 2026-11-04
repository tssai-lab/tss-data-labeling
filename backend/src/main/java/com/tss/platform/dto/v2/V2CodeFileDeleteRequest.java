package com.tss.platform.dto.v2;

/** File deletion request with workspace and content CAS evidence. */
public record V2CodeFileDeleteRequest(
        Long expectedWorkspaceRevision,
        String expectedContentHash
) {
}
