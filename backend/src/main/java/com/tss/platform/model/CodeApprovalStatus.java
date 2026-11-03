package com.tss.platform.model;

public final class CodeApprovalStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String REVOKED = "REVOKED";

    private CodeApprovalStatus() {
    }

    public static boolean isApproved(String status) {
        return APPROVED.equals(status);
    }
}
