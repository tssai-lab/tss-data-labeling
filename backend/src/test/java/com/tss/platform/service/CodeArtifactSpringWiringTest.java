package com.tss.platform.service;

import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeArtifactSpringWiringTest {

    @Test
    void archiveEngineIsDiscoverableAndAssemblerCanBeCreatedBySpring() {
        assertTrue(CodeZipArchiveService.class.isAnnotationPresent(Component.class));

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            assertNotNull(context.getBean(CodeZipArchiveService.class));
            assertNotNull(context.getBean(CodeArtifactAssembler.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            CodeZipArchiveService.class,
            CodeArtifactAssembler.class,
            CodePathPolicy.class,
            CodeFilePolicy.class
    })
    static class TestConfiguration {

        @Bean
        CodeArtifactStorageService codeArtifactStorageService() {
            return mock(CodeArtifactStorageService.class);
        }

        @Bean
        TrainingPlanRegistry trainingPlanRegistry() {
            return mock(TrainingPlanRegistry.class);
        }
    }
}
