package com.tss.platform.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class InternalCallbackTokenConfigurationValidator {

    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");

    private final TrainingKubernetesProperties properties;
    private final Environment environment;

    public InternalCallbackTokenConfigurationValidator(
            TrainingKubernetesProperties properties,
            Environment environment
    ) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        properties.requireInternalCallbackToken();
        if (properties.hasPublicDevelopmentInternalCallbackToken()
                && !hasDevelopmentProfile()) {
            throw new IllegalStateException(
                    "TRAINING_K8S_INTERNAL_CALLBACK_TOKEN must not use the public development token outside dev/local/test profiles"
            );
        }
    }

    private boolean hasDevelopmentProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (DEVELOPMENT_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }
}
