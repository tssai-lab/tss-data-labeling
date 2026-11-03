package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesTrainingExecutorTest {

    @Test
    void stopDelegatesOnlyTheDeterministicJobToTheSelectedClient() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setNamespace("tss-training");
        KubernetesWorkloadClient workloadClient = mock(KubernetesWorkloadClient.class);
        KubernetesTrainingExecutor executor = executor(
                properties,
                mock(TrainingExperimentVersionRepository.class),
                mock(KubernetesJobManifestBuilder.class),
                workloadClient
        );

        executor.stop("training-42");

        verify(workloadClient).deleteTrainingJob(
                "tss-training",
                KubernetesJobNaming.jobNameForTraining("training-42")
        );
    }

    @Test
    void stopFailureReachesTheCallerInsteadOfBeingReportedAsStopped() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        KubernetesWorkloadClient workloadClient = mock(KubernetesWorkloadClient.class);
        org.mockito.Mockito.doThrow(new KubernetesWorkloadException("unavailable"))
                .when(workloadClient)
                .deleteTrainingJob(
                        properties.getNamespace(),
                        KubernetesJobNaming.jobNameForTraining("training-42")
                );
        KubernetesTrainingExecutor executor = executor(
                properties,
                mock(TrainingExperimentVersionRepository.class),
                mock(KubernetesJobManifestBuilder.class),
                workloadClient
        );

        assertThrows(KubernetesWorkloadException.class, () -> executor.stop("training-42"));
    }

    @Test
    void submissionKeepsCurrentManifestAndNodeSelectionBeforeDelegating() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setNamespace("tss-training");
        TrainingExperimentVersionRepository repository = mock(TrainingExperimentVersionRepository.class);
        KubernetesJobManifestBuilder manifestBuilder = mock(KubernetesJobManifestBuilder.class);
        KubernetesWorkloadClient workloadClient = mock(KubernetesWorkloadClient.class);
        TrainingExperimentVersion task = mock(TrainingExperimentVersion.class);
        when(task.getServerIp()).thenReturn("10.0.0.42");
        when(task.getTrainingPlanId()).thenReturn("plan-1");
        when(repository.findById("training-42")).thenReturn(Optional.of(task));
        when(manifestBuilder.buildJobYaml(task, null, null, null, "10.0.0.42"))
                .thenReturn("job-yaml");
        KubernetesTrainingExecutor executor = executor(
                properties,
                repository,
                manifestBuilder,
                workloadClient
        );

        executor.submitJob("training-42");

        verify(workloadClient).applyTrainingJob(
                "tss-training",
                KubernetesJobNaming.jobNameForTraining("training-42"),
                "job-yaml"
        );
    }

    private KubernetesTrainingExecutor executor(
            TrainingKubernetesProperties properties,
            TrainingExperimentVersionRepository repository,
            KubernetesJobManifestBuilder manifestBuilder,
            KubernetesWorkloadClient workloadClient
    ) {
        return new KubernetesTrainingExecutor(
                properties,
                mock(TrainingEnvironmentService.class),
                repository,
                manifestBuilder,
                workloadClient,
                mock(TransactionTemplate.class)
        );
    }
}
