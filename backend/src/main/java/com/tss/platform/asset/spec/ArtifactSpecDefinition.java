package com.tss.platform.asset.spec;

public record ArtifactSpecDefinition(
        String id,
        AssetKind assetKind,
        AssetDirectoryCategory category,
        String displayName,
        Capability capability
) {

    public enum Capability {
        TRAINING_READY,
        STORAGE_ONLY,
        PLANNED
    }

    public boolean canBeAcceptedByTrainingPlan() {
        return capability == Capability.TRAINING_READY;
    }
}
