package com.tss.platform.module1.security;

public class UserAdministrationForbiddenException extends RuntimeException {

    public UserAdministrationForbiddenException(String message) {
        super(message);
    }
}
