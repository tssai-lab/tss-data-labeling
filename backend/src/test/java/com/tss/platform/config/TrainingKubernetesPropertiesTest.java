package com.tss.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingKubernetesPropertiesTest {

    @Test
    void defaultsToKubectlForBackwardCompatibility() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();

        assertEquals(TrainingKubernetesProperties.ClientMode.KUBECTL, properties.getClientMode());
        assertEquals(120, properties.getClientRequestTimeoutSeconds());
    }

    @Test
    void defaultConfigurationFilesDoNotExposePublicInternalCallbackTokenDefault() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String applicationDevYaml = Files.readString(Path.of("src/main/resources/application-dev.yml"));

        assertFalse(applicationYaml.contains("tss-internal-callback-dev"));
        assertFalse(applicationDevYaml.contains("tss-internal-callback-dev"));
    }

    @Test
    void requireInternalCallbackTokenRejectsBlankToken() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken(" ");

        assertThrows(
                IllegalStateException.class,
                properties::requireInternalCallbackToken
        );
    }

    @Test
    void validatorRejectsMissingTokenWhenKubernetesWorkerCallbacksAreEnabled() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setEnabled(true);
        properties.setInternalCallbackToken("");
        InternalCallbackTokenConfigurationValidator validator =
                new InternalCallbackTokenConfigurationValidator(properties, new MockEnvironment());

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void validatorRejectsPublicDevelopmentTokenOutsideDevelopmentProfiles() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setEnabled(true);
        properties.setInternalCallbackToken(
                TrainingKubernetesProperties.PUBLIC_DEVELOPMENT_INTERNAL_CALLBACK_TOKEN
        );
        InternalCallbackTokenConfigurationValidator validator =
                new InternalCallbackTokenConfigurationValidator(properties, new MockEnvironment());

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void validatorAllowsPublicDevelopmentTokenOnlyForDevelopmentProfiles() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setEnabled(true);
        properties.setInternalCallbackToken(
                TrainingKubernetesProperties.PUBLIC_DEVELOPMENT_INTERNAL_CALLBACK_TOKEN
        );
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        InternalCallbackTokenConfigurationValidator validator =
                new InternalCallbackTokenConfigurationValidator(properties, environment);

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void validatorSkipsTokenRequirementWhenKubernetesWorkerCallbacksAreDisabled() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setEnabled(false);
        properties.setInternalCallbackToken("");
        InternalCallbackTokenConfigurationValidator validator =
                new InternalCallbackTokenConfigurationValidator(properties, new MockEnvironment());

        assertDoesNotThrow(validator::validate);
    }
}
