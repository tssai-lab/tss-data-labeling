package com.tss.platform.module1.security;

public class UserApiPolicyConflictException extends RuntimeException {
    public UserApiPolicyConflictException(String message) {
        super(message);
    }
}
