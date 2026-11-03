package com.tss.platform.service;

import org.springframework.http.HttpStatus;

public class SampleFileException extends RuntimeException {

    private final HttpStatus status;
    private final Long rangeTotal;

    public SampleFileException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.rangeTotal = null;
    }

    public SampleFileException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.rangeTotal = null;
    }

    public SampleFileException(HttpStatus status, String message, Long rangeTotal) {
        super(message);
        this.status = status;
        this.rangeTotal = rangeTotal;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Long getRangeTotal() {
        return rangeTotal;
    }
}
