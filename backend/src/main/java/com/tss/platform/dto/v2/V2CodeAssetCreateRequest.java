package com.tss.platform.dto.v2;

/** Dedicated create request for a code asset. */
public record V2CodeAssetCreateRequest(
        String name,
        String trainingProfile,
        String purpose,
        String runtime,
        String entryScript,
        String trainingType,
        String remark
) {
}
