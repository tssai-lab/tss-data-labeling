package com.tss.platform.module1.sms;

public class SmsRateLimitException extends RuntimeException {

    public SmsRateLimitException(String message) {
        super(message);
    }
}
