package com.tss.platform.dto.v2;

import java.util.List;

public record V2DatasetEditability(
        boolean canCreateWorkspace,
        List<V2DatasetPublishBlocker> blockers
) {
    public V2DatasetEditability {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
