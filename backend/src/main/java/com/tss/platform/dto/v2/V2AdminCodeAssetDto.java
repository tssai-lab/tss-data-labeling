package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeAsset;

import java.time.Instant;

/** Administrator projection that exposes ownership but never storage details. */
public record V2AdminCodeAssetDto(
        String id,
        String name,
        Integer ownerUserId,
        String trainingProfile,
        String purpose,
        String runtime,
        String entryScript,
        String trainingType,
        String remark,
        long assetRevision,
        Instant createdAt,
        Instant updatedAt,
        boolean hasOpenWorkspace
) {

    public static V2AdminCodeAssetDto from(CodeAsset asset, boolean hasOpenWorkspace) {
        if (asset == null) {
            throw new IllegalArgumentException("Code asset is required");
        }
        return new V2AdminCodeAssetDto(
                asset.getId(),
                asset.getName(),
                asset.getOwnerUserId(),
                asset.getTrainingProfile(),
                asset.getPurpose(),
                asset.getRuntime(),
                asset.getEntryScript(),
                asset.getTrainingType(),
                asset.getRemark(),
                asset.getRowVersion() == null ? 0L : asset.getRowVersion(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                hasOpenWorkspace
        );
    }

    public static V2AdminCodeAssetDto from(
            V2CodeAssetDto asset,
            Integer ownerUserId
    ) {
        if (asset == null || ownerUserId == null) {
            throw new IllegalArgumentException("Administrator code asset scope is required");
        }
        return new V2AdminCodeAssetDto(
                asset.id(),
                asset.name(),
                ownerUserId,
                asset.trainingProfile(),
                asset.purpose(),
                asset.runtime(),
                asset.entryScript(),
                asset.trainingType(),
                asset.remark(),
                asset.assetRevision(),
                asset.createdAt(),
                asset.updatedAt(),
                asset.hasOpenWorkspace()
        );
    }
}
