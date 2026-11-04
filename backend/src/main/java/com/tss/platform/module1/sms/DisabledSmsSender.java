package com.tss.platform.module1.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledSmsSender implements SmsVerificationProvider {

    @Override
    public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
        throw new SmsServiceUnavailableException("短信服务尚未开通");
    }

    @Override
    public boolean verify(String mobile, String code) {
        throw new SmsServiceUnavailableException("短信服务尚未开通");
    }
}
