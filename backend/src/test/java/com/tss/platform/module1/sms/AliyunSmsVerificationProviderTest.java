package com.tss.platform.module1.sms;

import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponseBody;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.tss.platform.config.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunSmsVerificationProviderTest {

    @Test
    void springCreatesTheAliyunProviderWithTheProductionConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(AliyunProviderConfiguration.class)
                .withPropertyValues(
                        "sms.provider=aliyun",
                        "sms.aliyun.credential-type=ecs_ram_role",
                        "sms.aliyun.role-name=TSSAIPlatformSmsRole",
                        "sms.aliyun.sign-name=恒创联众")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AliyunSmsVerificationProvider.class);
                });
    }

    @Test
    void sendsSystemGeneratedSixDigitCodeWithoutReturningPlaintext() {
        AtomicReference<SendSmsVerifyCodeRequest> captured = new AtomicReference<>();
        AliyunSmsVerificationProvider provider = new AliyunSmsVerificationProvider(
                properties(), new StubClient() {
                    @Override
                    public SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) {
                        captured.set(request);
                        return sendResponse("OK", true);
                    }
                });

        SmsVerificationProvider.IssueReceipt receipt =
                provider.issue("13800002001", SmsPurpose.LOGIN_REGISTER, 300, 60);

        SendSmsVerifyCodeRequest request = captured.get();
        assertThat(receipt.localCode()).isNull();
        assertThat(request.getPhoneNumber()).isEqualTo("13800002001");
        assertThat(request.getCountryCode()).isEqualTo("86");
        assertThat(request.getSignName()).isEqualTo("系统赠送签名");
        assertThat(request.getTemplateCode()).isEqualTo("100001");
        assertThat(request.getTemplateParam()).isEqualTo("{\"code\":\"##code##\",\"min\":\"5\"}");
        assertThat(request.getCodeLength()).isEqualTo(6L);
        assertThat(request.getValidTime()).isEqualTo(300L);
        assertThat(request.getInterval()).isEqualTo(60L);
        assertThat(request.getDuplicatePolicy()).isEqualTo(1L);
        assertThat(request.getReturnVerifyCode()).isFalse();
    }

    @Test
    void acceptsOnlyPassAsAValidVerificationResult() {
        AtomicReference<CheckSmsVerifyCodeRequest> captured = new AtomicReference<>();
        AliyunSmsVerificationProvider provider = new AliyunSmsVerificationProvider(
                properties(), new StubClient() {
                    @Override
                    public CheckSmsVerifyCodeResponse check(CheckSmsVerifyCodeRequest request) {
                        captured.set(request);
                        return checkResponse("OK", true, "PASS");
                    }
                });

        assertThat(provider.verify("13800002002", "123456")).isTrue();
        assertThat(captured.get().getVerifyCode()).isEqualTo("123456");
        assertThat(captured.get().getSchemeName()).isEqualTo("tss-platform");

        AliyunSmsVerificationProvider rejected = new AliyunSmsVerificationProvider(
                properties(), new StubClient() {
                    @Override
                    public CheckSmsVerifyCodeResponse check(CheckSmsVerifyCodeRequest request) {
                        return checkResponse("OK", true, "UNKNOWN");
                    }
                });
        assertThat(rejected.verify("13800002002", "000000")).isFalse();
    }

    @Test
    void selectsTheSystemResetPasswordTemplateForResetCodes() {
        AtomicReference<SendSmsVerifyCodeRequest> captured = new AtomicReference<>();
        AliyunSmsVerificationProvider provider = new AliyunSmsVerificationProvider(
                properties(), new StubClient() {
                    @Override
                    public SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) {
                        captured.set(request);
                        return sendResponse("OK", true);
                    }
                });

        provider.issue("13800002004", SmsPurpose.RESET_PASSWORD, 300, 60);

        assertThat(captured.get().getTemplateCode()).isEqualTo("100003");
    }

    @Test
    void distinguishesProviderFailureFromWrongCode() {
        AliyunSmsVerificationProvider provider = new AliyunSmsVerificationProvider(
                properties(), new StubClient() {
                    @Override
                    public SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) {
                        return sendResponse("isv.SMS_SIGNATURE_ILLEGAL", false);
                    }
                });

        assertThatThrownBy(() -> provider.issue(
                "13800002003", SmsPurpose.LOGIN_REGISTER, 300, 60))
                .isInstanceOf(SmsProviderException.class)
                .hasMessageContaining("SMS_SIGNATURE_ILLEGAL");
    }

    @Test
    void failsClosedWhenAliyunConfigurationIsIncomplete() {
        SmsProperties.Aliyun properties = new SmsProperties.Aliyun();
        properties.setCredentialType("access_key");

        assertThatThrownBy(() -> new AliyunSmsVerificationProvider(properties, new StubClient()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-id")
                .hasMessageContaining("sign-name");
    }

    private static SendSmsVerifyCodeResponse sendResponse(String code, boolean success) {
        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody()
                .setCode(code)
                .setSuccess(success)
                .setRequestId("request-id");
        return new SendSmsVerifyCodeResponse().setBody(body);
    }

    private static CheckSmsVerifyCodeResponse checkResponse(String code, boolean success, String result) {
        CheckSmsVerifyCodeResponseBody.CheckSmsVerifyCodeResponseBodyModel model =
                new CheckSmsVerifyCodeResponseBody.CheckSmsVerifyCodeResponseBodyModel()
                        .setVerifyResult(result);
        CheckSmsVerifyCodeResponseBody body = new CheckSmsVerifyCodeResponseBody()
                .setCode(code)
                .setSuccess(success)
                .setModel(model);
        return new CheckSmsVerifyCodeResponse().setBody(body);
    }

    private static SmsProperties.Aliyun properties() {
        SmsProperties.Aliyun properties = new SmsProperties.Aliyun();
        properties.setAccessKeyId("access-key-id");
        properties.setAccessKeySecret("access-key-secret");
        properties.setSignName("系统赠送签名");
        properties.setLoginRegisterTemplateCode("100001");
        properties.setResetPasswordTemplateCode("100003");
        properties.setSchemeName("tss-platform");
        properties.setEndpoint("dypnsapi.aliyuncs.com");
        return properties;
    }

    private static class StubClient implements AliyunSmsVerificationProvider.AliyunPnvsClient {
        @Override
        public SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) {
            return sendResponse("OK", true);
        }

        @Override
        public CheckSmsVerifyCodeResponse check(CheckSmsVerifyCodeRequest request) {
            return checkResponse("OK", true, "PASS");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SmsProperties.class)
    @Import(AliyunSmsVerificationProvider.class)
    static class AliyunProviderConfiguration {
    }
}
