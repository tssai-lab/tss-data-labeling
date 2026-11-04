package com.tss.platform.dto.v2;

public record V2DatasetMutationResult<T>(
        String workspaceId,
        long workspaceRevision,
        T resource
) {
}
