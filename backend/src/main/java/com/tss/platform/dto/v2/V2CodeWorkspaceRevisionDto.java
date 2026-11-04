package com.tss.platform.dto.v2;

/** Minimal mutation acknowledgement for operations that do not return file content. */
public record V2CodeWorkspaceRevisionDto(String workspaceId, long workspaceRevision) {
}
