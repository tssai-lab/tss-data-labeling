package com.tss.platform.service;

public class CodeAssetImportException extends RuntimeException {

    public CodeAssetImportException() {
        super("Code asset import could not be completed");
    }
}
