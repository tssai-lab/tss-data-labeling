package com.tss.platform.service;

public class AssetNameConflictException extends IllegalArgumentException {

    private final String assetType;

    public AssetNameConflictException(String assetType) {
        super(assetType + " asset name already exists");
        this.assetType = assetType;
    }

    public String getAssetType() {
        return assetType;
    }
}
