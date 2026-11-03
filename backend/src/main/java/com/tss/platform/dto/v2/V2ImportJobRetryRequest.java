package com.tss.platform.dto.v2;

public record V2ImportJobRetryRequest(
        String mode,
        Long expectedWorkspaceRevision
) {
}
