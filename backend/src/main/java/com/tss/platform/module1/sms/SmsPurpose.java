package com.tss.platform.module1.sms;

public enum SmsPurpose {
    LOGIN_REGISTER,
    RESET_PASSWORD;

    public static SmsPurpose from(String value) {
        if (value == null || value.isBlank()) {
            return LOGIN_REGISTER;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("短信验证码用途不正确");
        }
    }
}
