package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "training.mlflow")
public class TrainingMlflowProperties {
    private boolean enabled = true;
    private String trackingUri = "http://127.0.0.1:5000";
    private String experimentName = "tss-training";
}
