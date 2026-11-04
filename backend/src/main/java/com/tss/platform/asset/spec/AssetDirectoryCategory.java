package com.tss.platform.asset.spec;

import java.util.EnumSet;
import java.util.Set;

/**
 * User-facing asset catalogue categories. A category is descriptive metadata only;
 * it never grants training compatibility.
 */
public enum AssetDirectoryCategory {
    CV(EnumSet.of(AssetKind.MODEL, AssetKind.DATASET)),
    NLP(EnumSet.of(AssetKind.MODEL, AssetKind.DATASET)),
    POINT_CLOUD(EnumSet.of(AssetKind.DATASET)),
    ROBOT(EnumSet.of(AssetKind.DATASET)),
    MULTIMODAL(EnumSet.of(AssetKind.DATASET)),
    OTHER(EnumSet.of(AssetKind.MODEL, AssetKind.DATASET));

    private final Set<AssetKind> supportedKinds;

    AssetDirectoryCategory(Set<AssetKind> supportedKinds) {
        this.supportedKinds = Set.copyOf(supportedKinds);
    }

    public boolean supports(AssetKind kind) {
        return kind != null && supportedKinds.contains(kind);
    }
}
