package com.tss.platform.inference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuInferenceImageConfigurationTest {

    private static final String LOCKED_GPU_IMAGE =
            "crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/"
                    + "tss-inference-worker-gpu@sha256:"
                    + "7354987c572dfca03f027442cbd034483f4c915eecf1e4df139e01b35b33c114";

    @Test
    void usesLockedGpuImageWhenDeploymentDoesNotOverrideIt() throws IOException {
        MockEnvironment environment = applicationEnvironment();

        assertEquals(
                LOCKED_GPU_IMAGE,
                environment.getProperty("inference.kubernetes.gpu-worker-image")
        );
    }

    @Test
    void allowsDeploymentToOverrideGpuImage() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("INFERENCE_KUBERNETES_GPU_WORKER_IMAGE", "registry.internal/gpu@sha256:test");
        loadApplicationProperties(environment);

        assertEquals(
                "registry.internal/gpu@sha256:test",
                environment.getProperty("inference.kubernetes.gpu-worker-image")
        );
    }

    private static MockEnvironment applicationEnvironment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        loadApplicationProperties(environment);
        return environment;
    }

    private static void loadApplicationProperties(MockEnvironment environment) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
    }
}
