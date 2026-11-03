package com.tss.platform.service;

public class CodeArtifactStorageException extends RuntimeException {

    public CodeArtifactStorageException() {
        super("Code artifact storage operation failed");
    }
}
