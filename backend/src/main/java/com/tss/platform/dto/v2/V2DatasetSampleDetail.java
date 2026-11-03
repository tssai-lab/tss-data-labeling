package com.tss.platform.dto.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record V2DatasetSampleDetail(
        String sampleId,
        String workspaceId,
        String externalId,
        Integer sampleIndex,
        Map<String, Object> tags,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted,
        Instant deletedAt,
        List<V2DatasetDataResource> data,
        List<V2DatasetAnnotationResource> annotations
) {
}
