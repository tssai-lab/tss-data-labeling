package com.tss.platform.modelcache;

import java.time.Instant;

public record ModelCachePolicy(
        long maxBytes,
        long minFreeBytes,
        long runtimeReserveBytes,
        Instant updatedAt
) {
    public long emptyCacheGateBytes() {
        return Math.addExact(Math.addExact(maxBytes, minFreeBytes), runtimeReserveBytes);
    }

    public long requiredAvailableBytes(long usedBytes) {
        if (usedBytes < 0 || usedBytes > maxBytes) {
            throw new IllegalArgumentException("缓存占用超出新缓存上限");
        }
        return Math.addExact(
                Math.addExact(maxBytes - usedBytes, minFreeBytes),
                runtimeReserveBytes
        );
    }
}
