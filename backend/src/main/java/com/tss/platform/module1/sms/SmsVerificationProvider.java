package com.tss.platform.module1.sms;

/**
 * 短信验证码供应商边界。真实供应商负责生成并校验验证码；只有隔离的本地开发模式返回明文验证码。
 */
public interface SmsVerificationProvider {

    IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds);

    boolean verify(String mobile, String code);

    record IssueReceipt(String localCode) {

        public static IssueReceipt remote() {
            return new IssueReceipt(null);
        }

        public static IssueReceipt local(String code) {
            return new IssueReceipt(code);
        }
    }
}
