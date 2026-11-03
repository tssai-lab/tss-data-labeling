package com.tss.platform.dto.v2;

public record V2DatasetContentUpdateRequest(
        String content,
        String fileName,
        String format,
        String contentType,
        Long expectedWorkspaceRevision
) {
}
