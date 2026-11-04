package com.tss.platform.dto.v2;

import java.time.Instant;

public record V2DatasetWorkspaceAbandonResult(
        String workspaceId,
        String datasetId,
        String status,
        Instant abandonedAt,
        long workspaceRevision
) {
}
