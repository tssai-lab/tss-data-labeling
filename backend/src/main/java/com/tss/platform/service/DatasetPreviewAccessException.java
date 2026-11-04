package com.tss.platform.service;

/**
 * Classifies dataset preview access failures while preserving the legacy
 * {@link IllegalArgumentException} contract used by V1 controllers.
 */
public class DatasetPreviewAccessException extends IllegalArgumentException {

    public enum Reason {
        NOT_FOUND,
        NOT_PREVIEWABLE
    }

    private final Reason reason;

    public DatasetPreviewAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DatasetPreviewAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
