package com.tss.platform.dto.v2;

import java.util.Map;

public record V2DatasetSampleCreateRequest(
        String externalId,
        Map<String, Object> tags,
        Map<String, Object> metadata,
        Long expectedWorkspaceRevision
) {
}
