package com.tss.platform.service;

import java.util.Objects;

/** Stable optimistic-concurrency or workspace-state conflict. */
public class CodeWorkspaceConflictException extends RuntimeException {

    private final String reasonCode;

    public CodeWorkspaceConflictException(String reasonCode, String message) {
        super(message);
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
