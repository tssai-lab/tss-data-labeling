package com.tss.platform.dto.v2;

import java.util.Map;

public record V2DatasetInlineDataCreateRequest(
        String content,
        String dataType,
        String sensor,
        String channel,
        Integer seq,
        String format,
        String fileName,
        String contentType,
        Map<String, Object> metadata,
        Long expectedWorkspaceRevision
) {
}
