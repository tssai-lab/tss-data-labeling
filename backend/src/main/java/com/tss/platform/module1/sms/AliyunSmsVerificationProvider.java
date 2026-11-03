package com.tss.platform.module1.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponseBody;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.tss.platform.config.SmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "aliyun")
public class AliyunSmsVerificationProvider implements SmsVerificationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AliyunSmsVerificationProvider.class);
    private static final String SUCCESS_CODE = "OK";
    private static final String VERIFY_PASS = "PASS";
    private static final String COUNTRY_CODE = "86";

    private final SmsProperties.Aliyun properties;
    private final AliyunPnvsClient client;

    @Autowired
    public AliyunSmsVerificationProvider(SmsProperties smsProperties) {
        this(smsProperties.getAliyun(), createClient(smsProperties.getAliyun()));
    }

    AliyunSmsVerificationProvider(SmsProperties.Aliyun properties, AliyunPnvsClient client) {
        validate(properties);
        this.properties = properties;
        this.client = client;
    }

    @Override
    public IssueReceipt issue(String mobile, SmsPurpose purpose, int expireSeconds, int resendSeconds) {
        try {
            SendSmsVerifyCodeResponse response = client.send(createIssueRequest(mobile, purpose, expireSeconds, resendSeconds));
            SendSmsVerifyCodeResponseBody body = response == null ? null : response.getBody();
            if (body == null || !SUCCESS_CODE.equalsIgnoreCase(body.getCode()) || !Boolean.TRUE.equals(body.getSuccess())) {
                String providerCode = body == null ? "EMPTY_RESPONSE" : safe(body.getCode());
                LOG.warn("阿里云验证码发送被拒绝: providerCode={}, requestId={}",
                        providerCode, body == null ? "-" : safe(body.getRequestId()));
                throw new SmsProviderException("阿里云验证码发送失败: " + providerCode);
            }
            return IssueReceipt.remote();
        } catch (SmsProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            LOG.warn("阿里云验证码发送请求异常: type={}", exception.getClass().getSimpleName());
            throw new SmsProviderException("阿里云验证码发送请求失败", exception);
        }
    }

    @Override
    public boolean verify(String mobile, String code) {
        try {
            CheckSmsVerifyCodeResponse response = client.check(createCheckRequest(mobile, code));
            CheckSmsVerifyCodeResponseBody body = response == null ? null : response.getBody();
            if (body == null || !SUCCESS_CODE.equalsIgnoreCase(body.getCode()) || !Boolean.TRUE.equals(body.getSuccess())) {
                String providerCode = body == null ? "EMPTY_RESPONSE" : safe(body.getCode());
                LOG.warn("阿里云验证码校验请求失败: providerCode={}", providerCode);
                throw new SmsProviderException("阿里云验证码校验请求失败: " + providerCode);
            }
            return body.getModel() != null && VERIFY_PASS.equalsIgnoreCase(body.getModel().getVerifyResult());
        } catch (SmsProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            LOG.warn("阿里云验证码校验请求异常: type={}", exception.getClass().getSimpleName());
            throw new SmsProviderException("阿里云验证码校验请求失败", exception);
        }
    }

    SendSmsVerifyCodeRequest createIssueRequest(
            String mobile,
            SmsPurpose purpose,
            int expireSeconds,
            int resendSeconds) {
        int expireMinutes = Math.max(1, expireSeconds / 60);
        SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                .setPhoneNumber(mobile)
                .setCountryCode(COUNTRY_CODE)
                .setSignName(properties.getSignName())
                .setTemplateCode(templateCode(purpose))
                .setTemplateParam("{\"code\":\"##code##\",\"min\":\"" + expireMinutes + "\"}")
                .setCodeLength(6L)
                .setCodeType(1L)
                .setValidTime((long) expireSeconds)
                .setInterval((long) resendSeconds)
                .setDuplicatePolicy(1L)
                .setReturnVerifyCode(false);
        if (properties.getSchemeName() != null && !properties.getSchemeName().isBlank()) {
            request.setSchemeName(properties.getSchemeName().trim());
        }
        return request;
    }

    private String templateCode(SmsPurpose purpose) {
        return purpose == SmsPurpose.RESET_PASSWORD
                ? properties.getResetPasswordTemplateCode()
                : properties.getLoginRegisterTemplateCode();
    }

    CheckSmsVerifyCodeRequest createCheckRequest(String mobile, String code) {
        CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                .setPhoneNumber(mobile)
                .setCountryCode(COUNTRY_CODE)
                .setVerifyCode(code)
                .setCaseAuthPolicy(1L);
        if (properties.getSchemeName() != null && !properties.getSchemeName().isBlank()) {
            request.setSchemeName(properties.getSchemeName().trim());
        }
        return request;
    }

    private static AliyunPnvsClient createClient(SmsProperties.Aliyun properties) {
        validate(properties);
        try {
            Config config = new Config()
                    .setCredential(createCredentialClient(properties))
                    .setEndpoint(properties.getEndpoint());
            Client sdkClient = new Client(config);
            return new AliyunPnvsClient() {
                @Override
                public SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) throws Exception {
                    return sdkClient.sendSmsVerifyCode(request);
                }

                @Override
                public CheckSmsVerifyCodeResponse check(CheckSmsVerifyCodeRequest request) throws Exception {
                    return sdkClient.checkSmsVerifyCode(request);
                }
            };
        } catch (Exception exception) {
            throw new IllegalStateException("阿里云号码认证客户端初始化失败", exception);
        }
    }

    private static com.aliyun.credentials.Client createCredentialClient(SmsProperties.Aliyun properties) {
        com.aliyun.credentials.models.Config credentialConfig = new com.aliyun.credentials.models.Config();
        if ("access_key".equalsIgnoreCase(properties.getCredentialType())) {
            credentialConfig.setType("access_key")
                    .setAccessKeyId(properties.getAccessKeyId())
                    .setAccessKeySecret(properties.getAccessKeySecret());
        } else {
            credentialConfig.setType("ecs_ram_role").setDisableIMDSv1(false);
            if (properties.getRoleName() != null && !properties.getRoleName().isBlank()) {
                credentialConfig.setRoleName(properties.getRoleName().trim());
            }
        }
        return new com.aliyun.credentials.Client(credentialConfig);
    }

    private static void validate(SmsProperties.Aliyun properties) {
        List<String> missing = new ArrayList<>();
        String credentialType = properties.getCredentialType();
        if (!"ecs_ram_role".equalsIgnoreCase(credentialType)
                && !"access_key".equalsIgnoreCase(credentialType)) {
            throw new IllegalStateException("阿里云凭据类型只支持 ecs_ram_role 或 access_key");
        }
        if ("access_key".equalsIgnoreCase(credentialType)) {
            addIfBlank(missing, "access-key-id", properties.getAccessKeyId());
            addIfBlank(missing, "access-key-secret", properties.getAccessKeySecret());
        }
        addIfBlank(missing, "sign-name", properties.getSignName());
        addIfBlank(missing, "login-register-template-code", properties.getLoginRegisterTemplateCode());
        addIfBlank(missing, "reset-password-template-code", properties.getResetPasswordTemplateCode());
        addIfBlank(missing, "endpoint", properties.getEndpoint());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("阿里云号码认证配置不完整: " + String.join(", ", missing));
        }
    }

    private static void addIfBlank(List<String> missing, String name, String value) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    interface AliyunPnvsClient {
        SendSmsVerifyCodeResponse send(SendSmsVerifyCodeRequest request) throws Exception;

        CheckSmsVerifyCodeResponse check(CheckSmsVerifyCodeRequest request) throws Exception;
    }
}
