package com.tss.platform.module1.sms;

/** 供应商网络故障、配置错误或业务拒绝；不等同于“验证码错误”。 */
public class SmsProviderException extends RuntimeException {

    public SmsProviderException(String message) {
        super(message);
    }

    public SmsProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
