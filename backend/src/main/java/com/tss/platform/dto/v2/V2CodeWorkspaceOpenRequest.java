package com.tss.platform.dto.v2;

/** Opens the single active workspace, optionally from an immutable base version. */
public record V2CodeWorkspaceOpenRequest(String baseVersionId) {
}
