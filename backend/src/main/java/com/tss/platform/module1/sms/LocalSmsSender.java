package com.tss.platform.module1.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "local")
public class LocalSmsSender implements SmsVerificationProvider {

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        return IssueReceipt.local(code);
    }

    @Override
    public boolean verify(String mobile, String code) {
        throw new IllegalStateException("本地验证码应由平台内存校验，不应调用供应商校验");
    }
}
