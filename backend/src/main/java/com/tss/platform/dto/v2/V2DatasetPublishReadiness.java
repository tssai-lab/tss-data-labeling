package com.tss.platform.dto.v2;

import java.util.List;

public record V2DatasetPublishReadiness(
        boolean canPublish,
        long evaluatedRevision,
        List<V2DatasetPublishBlocker> blockers
) {
    public V2DatasetPublishReadiness {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
