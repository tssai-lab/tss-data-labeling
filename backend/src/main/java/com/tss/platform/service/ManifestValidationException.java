package com.tss.platform.service;

import java.util.Map;

public class ManifestValidationException extends IllegalArgumentException {

    private final String errorCode;
    private final Map<String, Object> details;
    private final ManifestFailureKind failureKind;

    public ManifestValidationException(String message) {
        this("INVALID_MANIFEST", message, Map.of(), null);
    }

    public ManifestValidationException(String message, Throwable cause) {
        this("INVALID_MANIFEST", message, Map.of(), cause);
    }

    public ManifestValidationException(
            String errorCode,
            String message,
            Map<String, Object> details
    ) {
        this(errorCode, message, details, null);
    }

    public ManifestValidationException(
            String errorCode,
            String message,
            Map<String, Object> details,
            Throwable cause
    ) {
        this(errorCode, message, details, cause, null);
    }

    private ManifestValidationException(String errorCode, String message,
            Map<String, Object> details, Throwable cause, ManifestFailureKind failureKind) {
        super(message, cause);
        this.failureKind = failureKind;
        this.errorCode = errorCode == null || errorCode.isBlank()
                ? "INVALID_MANIFEST"
                : errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** 新解析路径必须显式分类；旧构造器仅供兼容，不改变原 getErrorCode 契约。 */
    public static ManifestValidationException classified(ManifestFailureKind kind,
            String message, Map<String, Object> details) {
        return classified(kind, message, details, null);
    }

    public static ManifestValidationException classified(ManifestFailureKind kind,
            String message, Map<String, Object> details, Throwable cause) {
        return new ManifestValidationException("INVALID_MANIFEST", message, details, cause,
                java.util.Objects.requireNonNull(kind));
    }

    public ManifestFailureKind getFailureKind() {
        return failureKind;
    }
}
