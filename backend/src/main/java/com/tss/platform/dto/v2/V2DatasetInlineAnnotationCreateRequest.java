package com.tss.platform.dto.v2;

import java.util.Map;

public record V2DatasetInlineAnnotationCreateRequest(
        String content,
        String sampleDataId,
        String annotationType,
        String format,
        String fileName,
        String contentType,
        Map<String, Object> metadata,
        Long expectedWorkspaceRevision
) {
}
