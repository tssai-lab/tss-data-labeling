package com.tss.platform.service;

/** Raised when a code file cannot be previewed or mutated online. */
public class CodeContentTooLargeException extends RuntimeException {

    public static final String REASON_CODE = "FILE_TOO_LARGE";

    public CodeContentTooLargeException() {
        super("Code file exceeds the online content limit");
    }

    public String getReasonCode() {
        return REASON_CODE;
    }
}
