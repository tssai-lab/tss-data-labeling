package com.tss.platform.service;

public class ModelArtifactException extends IllegalArgumentException {

    private final boolean storageUnavailable;

    ModelArtifactException(String message, boolean storageUnavailable) {
        super(message);
        this.storageUnavailable = storageUnavailable;
    }

    ModelArtifactException(String message, boolean storageUnavailable, Throwable cause) {
        super(message, cause);
        this.storageUnavailable = storageUnavailable;
    }

    public boolean isStorageUnavailable() {
        return storageUnavailable;
    }
}
