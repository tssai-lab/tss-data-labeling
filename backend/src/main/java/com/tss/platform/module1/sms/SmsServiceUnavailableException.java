package com.tss.platform.module1.sms;

public class SmsServiceUnavailableException extends RuntimeException {

    public SmsServiceUnavailableException(String message) {
        super(message);
    }
}
