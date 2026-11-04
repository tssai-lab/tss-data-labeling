package com.tss.platform.dto.v2;

import java.util.Map;

public record V2DatasetWorkspaceFileUploadInitRequest(
        String targetKind,
        String targetOperation,
        String sampleId,
        String resourceId,
        String fileName,
        Long fileSize,
        String sha256,
        String format,
        String contentType,
        String dataType,
        String sensor,
        String channel,
        Integer seq,
        String sampleDataId,
        String annotationType,
        Map<String, Object> metadata,
        Long expectedWorkspaceRevision
) {
}
