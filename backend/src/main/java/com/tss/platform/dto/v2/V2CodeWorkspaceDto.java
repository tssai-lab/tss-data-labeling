package com.tss.platform.dto.v2;

import java.time.Instant;

/** Public workspace projection. Ownership and soft-delete fields are intentionally absent. */
public record V2CodeWorkspaceDto(
        String id,
        String assetId,
        String baseVersionId,
        String closedVersionId,
        String status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        boolean readOnly
) {
}
