package com.tss.platform.dto.v2;

import java.time.Instant;
import java.util.Map;

public record V2DatasetDataResource(
        String dataId,
        String sampleId,
        String dataType,
        String sensor,
        String channel,
        Integer seq,
        String format,
        String fileName,
        Long sizeBytes,
        String checksum,
        String contentType,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted,
        Instant deletedAt
) {
}
