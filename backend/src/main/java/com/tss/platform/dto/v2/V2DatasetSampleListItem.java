package com.tss.platform.dto.v2;

import java.time.Instant;
import java.util.Map;

public record V2DatasetSampleListItem(
        String sampleId,
        String workspaceId,
        String externalId,
        Integer sampleIndex,
        Map<String, Object> tags,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted,
        Instant deletedAt
) {
}
