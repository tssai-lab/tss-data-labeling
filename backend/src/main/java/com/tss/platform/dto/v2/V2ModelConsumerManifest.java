package com.tss.platform.dto.v2;

import java.util.Map;

public record V2ModelConsumerManifest(
        String modelAssetId,
        String modelVersionId,
        String version,
        String status,
        String type,
        String fileName,
        long sizeBytes,
        String artifactSha256,
        String commitInfo,
        Map<String, Object> hyperParams,
        boolean isCurrent,
        String downloadUrl,
        String filesUrl
) {
}
