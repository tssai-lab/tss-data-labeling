package com.tss.platform.controller.v2;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class V2BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> details;

    public V2BusinessException(
            HttpStatus status,
            String errorCode,
            String errorMessage,
            Map<String, Object> details
    ) {
        super(errorMessage);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public V2BusinessException(
            HttpStatus status,
            String errorCode,
            String errorMessage
    ) {
        this(status, errorCode, errorMessage, Map.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
