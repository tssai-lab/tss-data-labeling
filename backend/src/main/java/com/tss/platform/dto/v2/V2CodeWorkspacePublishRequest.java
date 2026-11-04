package com.tss.platform.dto.v2;

public record V2CodeWorkspacePublishRequest(
        Long expectedWorkspaceRevision,
        String version
) {
}
