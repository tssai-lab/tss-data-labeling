package com.tss.platform.dto.v2;

/** UTF-8 file create/update request with workspace and content CAS evidence. */
public record V2CodeFileUpsertRequest(
        String content,
        Long expectedWorkspaceRevision,
        String expectedContentHash
) {
}
