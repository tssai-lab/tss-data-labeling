package com.tss.platform;

import com.tss.platform.config.AuthSessionProperties;
import com.tss.platform.config.ComputeProperties;
import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.SmsProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.config.TrainingMlflowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(excludeName = "cn.dev33.satoken.dao.SaTokenDaoRedisJackson")
@EnableConfigurationProperties({
        AuthSessionProperties.class,
        TrainingMlflowProperties.class,
        TrainingKubernetesProperties.class,
        ComputeProperties.class,
        SmsProperties.class,
        InferenceModelCacheProperties.class,
        InferenceKubernetesResourceProperties.class
})
public class TssPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(TssPlatformApplication.class, args);
    }
}
