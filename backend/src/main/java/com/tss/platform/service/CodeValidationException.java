package com.tss.platform.service;

import java.util.Objects;

/**
 * Stable validation failure raised by code asset file and archive policies.
 */
public class CodeValidationException extends RuntimeException {

    private final String reasonCode;

    public CodeValidationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public CodeValidationException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
