package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "sms")
public class SmsProperties {

    /** disabled, local (isolated development only), or aliyun. */
    private String provider = "disabled";

    /** Only allowed with the local provider; never enable on Main. */
    private boolean exposeCode = false;

    /** Single-instance cost guard. Non-positive legacy values fall back to 50. */
    private int maxDailySends = 50;

    private Aliyun aliyun = new Aliyun();

    @Getter
    @Setter
    public static class Aliyun {
        private String credentialType = "ecs_ram_role";
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String roleName = "";
        private String signName = "";
        private String loginRegisterTemplateCode = "100001";
        private String resetPasswordTemplateCode = "100003";
        private String schemeName = "";
        private String endpoint = "dypnsapi.aliyuncs.com";
    }
}
