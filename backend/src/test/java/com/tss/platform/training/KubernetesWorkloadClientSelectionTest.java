package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KubernetesWorkloadClientSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToExactlyOneKubectlImplementation() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KubernetesWorkloadClient.class);
            assertThat(context).hasSingleBean(KubectlKubernetesWorkloadClient.class);
            assertThat(context).doesNotHaveBean(Fabric8KubernetesWorkloadClient.class);
        });
    }

    @Test
    void fabric8ModeLoadsExactlyOneFabric8Implementation() {
        contextRunner
                .withPropertyValues("training.kubernetes.client-mode=fabric8")
                .run(context -> {
                    assertThat(context).hasSingleBean(KubernetesWorkloadClient.class);
                    assertThat(context).hasSingleBean(Fabric8KubernetesWorkloadClient.class);
                    assertThat(context).doesNotHaveBean(KubectlKubernetesWorkloadClient.class);
                });
    }

    @Test
    void invalidModeFailsAtStartupInsteadOfFallingBack() {
        contextRunner
                .withPropertyValues("training.kubernetes.client-mode=automatic")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidClientTimeoutFailsAtStartup() {
        contextRunner
                .withPropertyValues("training.kubernetes.client-request-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TrainingKubernetesProperties.class)
    @Import({
            KubectlKubernetesWorkloadClient.class,
            Fabric8KubernetesClientProvider.class,
            Fabric8KubernetesWorkloadClient.class
    })
    static class TestConfiguration {

        @Bean
        TrainingEnvironmentService trainingEnvironmentService() {
            return mock(TrainingEnvironmentService.class);
        }

        @Bean
        ShellCommandRunner shellCommandRunner() {
            return mock(ShellCommandRunner.class);
        }
    }
}
