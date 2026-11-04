package com.tss.platform.service;

public class CodeWorkspacePublishException extends RuntimeException {

    public CodeWorkspacePublishException() {
        super("Code workspace publication failed");
    }
}
