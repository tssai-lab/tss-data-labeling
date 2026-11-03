package com.tss.platform.service;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobTtlPolicyServiceTest {

    @Test
    void databaseValueOverridesStaticConfigurationForNewJobs() {
        PlatformSystemConfigRepository repository = mock(PlatformSystemConfigRepository.class);
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setJobTtlSecondsAfterFinished(3600);
        PlatformSystemConfig config = new PlatformSystemConfig();
        config.setJobTtlSecondsAfterFinished(180);
        when(repository.findById(PlatformSystemConfig.GLOBAL_ID)).thenReturn(Optional.of(config));

        assertEquals(
                180,
                new JobTtlPolicyService(repository, properties).currentJobTtlSecondsAfterFinished()
        );
    }

    @Test
    void missingOrUnsafeStoredValueFallsBackSafely() {
        PlatformSystemConfigRepository repository = mock(PlatformSystemConfigRepository.class);
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setJobTtlSecondsAfterFinished(300);
        when(repository.findById(PlatformSystemConfig.GLOBAL_ID)).thenReturn(Optional.empty());

        assertEquals(
                300,
                new JobTtlPolicyService(repository, properties).currentJobTtlSecondsAfterFinished()
        );
    }

    @Test
    void transientDatabaseFailureDoesNotBlockJobManifestCreation() {
        PlatformSystemConfigRepository repository = mock(PlatformSystemConfigRepository.class);
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setJobTtlSecondsAfterFinished(240);
        when(repository.findById(PlatformSystemConfig.GLOBAL_ID))
                .thenThrow(new IllegalStateException("temporary database failure"));

        assertEquals(
                240,
                new JobTtlPolicyService(repository, properties).currentJobTtlSecondsAfterFinished()
        );
    }
}
