package com.tss.platform.dto.modelcache;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ModelCacheDtos {

    private ModelCacheDtos() {
    }

    public record Entry(
            String sha256,
            String storagePath,
            long artifactSizeBytes,
            long dataSizeBytes,
            long diskSizeBytes,
            long createdAtEpochSeconds,
            long lastUsedAtEpochSeconds,
            boolean inUse,
            boolean valid
    ) {
    }

    public record Node(
            String serverIp,
            String hostname,
            String k8sNodeName,
            boolean cacheReady,
            long usedBytes,
            long diskFreeBytes,
            long diskTotalBytes,
            long requiredAvailableBytes,
            long policyHeadroomBytes,
            List<Entry> entries,
            String error
    ) {
    }

    public record Overview(
            boolean enabled,
            long maxBytes,
            long minFreeBytes,
            long runtimeReserveBytes,
            long emptyCacheGateBytes,
            Instant policyUpdatedAt,
            List<Node> nodes
    ) {
    }

    public record PolicyUpdateRequest(
            Long maxBytes,
            Long minFreeBytes,
            Long runtimeReserveBytes
    ) {
    }

    @Data
    public static class ClearRequest {
        private List<String> serverIps = new ArrayList<>();
        private List<String> sha256s = new ArrayList<>();
        private boolean clearAll;
    }

    public record ClearNodeResult(
            String serverIp,
            String k8sNodeName,
            List<String> cleared,
            List<String> inUse,
            List<String> notFound,
            String error
    ) {
    }

    public record ClearResponse(
            List<ClearNodeResult> nodes
    ) {
    }
}
