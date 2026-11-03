package com.tss.platform.service;

public class CodeApprovalForbiddenException extends RuntimeException {

    public CodeApprovalForbiddenException() {
        super("Administrator approval authority is required");
    }
}
