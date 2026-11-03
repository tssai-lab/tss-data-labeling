package com.tss.platform.service;

/**
 * Non-enumerating code asset lookup failure. The message is deliberately
 * stable and must not contain identifiers or internal object names.
 */
public class CodeAssetAccessException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Code asset resource was not found";

    public CodeAssetAccessException() {
        super(SAFE_MESSAGE);
    }
}
