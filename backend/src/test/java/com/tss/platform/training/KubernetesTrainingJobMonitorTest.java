package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.service.JobScheduler;
import com.tss.platform.training.plan.TrainingRunSpecCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesTrainingJobMonitorTest {

    private TrainingKubernetesProperties properties;
    private TrainingEnvironmentService environmentService;
    private TrainingExperimentVersionRepository repository;
    private TransactionTemplate transactionTemplate;
    private TrainingFailureDiagnosticService diagnosticService;
    private KubernetesWorkloadClient workloadClient;
    private JobScheduler jobScheduler;
    private MutableClock clock;
    private KubernetesTrainingJobMonitor monitor;

    @BeforeEach
    void setUp() {
        properties = new TrainingKubernetesProperties();
        properties.setEnabled(true);
        properties.setFailureDiagnosticsEnabled(true);
        properties.setFailureDiagnosticsRetryWindowSeconds(900);
        environmentService = mock(TrainingEnvironmentService.class);
        repository = mock(TrainingExperimentVersionRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        diagnosticService = mock(TrainingFailureDiagnosticService.class);
        workloadClient = mock(KubernetesWorkloadClient.class);
        jobScheduler = mock(JobScheduler.class);
        clock = new MutableClock(Instant.parse("2026-09-02T12:00:00Z"));
        monitor = new KubernetesTrainingJobMonitor(
                properties,
                environmentService,
                repository,
                workloadClient,
                mock(TrainingRunSpecCodec.class),
                transactionTemplate,
                jobScheduler,
                diagnosticService,
                clock
        );
        when(environmentService.isKubernetesReady()).thenReturn(true);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(repository
                .findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
                        any(),
                        any(Instant.class)
                ))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void retriesWorkerCallbackRaceAndAttachesArchivedLog() {
        TrainingExperimentVersion task = failedTask("train-race");
        task.setServerIp(null);
        when(repository
                .findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
                        any(),
                        any(Instant.class)
                ))
                .thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        when(diagnosticService.archive(task, KubernetesJobNaming.jobNameForTraining(task.getId())))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(
                        true,
                        "minio://users/7/training-failure-diagnostics/train-race/failure.log"
                ));

        monitor.syncJobStatuses();

        assertEquals(
                "minio://users/7/training-failure-diagnostics/train-race/failure.log",
                task.getLogPath()
        );
        verify(repository).save(task);
    }

    @Test
    void unattachedObjectIsQueuedForDeletion() {
        TrainingExperimentVersion task = failedTask("train-deleted");
        when(repository
                .findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
                        any(),
                        any(Instant.class)
                ))
                .thenReturn(List.of(task));
        when(repository.findById(task.getId()))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.empty());
        String logPath = "minio://users/7/training-failure-diagnostics/train-deleted/failure.log";
        when(diagnosticService.archive(task, KubernetesJobNaming.jobNameForTraining(task.getId())))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(true, logPath));

        monitor.syncJobStatuses();

        verify(diagnosticService).enqueueDeletion(task, logPath);
    }

    @Test
    void persistentImagePullFailureMarksTaskFailedArchivesEvidenceAndDeletesJob() {
        TrainingExperimentVersion task = activeTask("train-image-pull", "running");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenReturn(Optional.of(new KubernetesWorkloadClient.TrainingJobStatus(
                        0,
                        0,
                        1,
                        "ImagePullBackOff",
                        "pull access denied",
                        Instant.now().minusSeconds(180)
                )));
        String logPath = "minio://users/7/training-failure-diagnostics/train-image-pull/failure.log";
        when(diagnosticService.archive(task, jobName))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(true, logPath));

        monitor.syncJobStatuses();
        assertEquals("running", task.getStatus());
        clock.advanceSeconds(121);
        monitor.syncJobStatuses();

        assertEquals("failed", task.getStatus());
        assertEquals("Pod 启动失败: ImagePullBackOff - pull access denied", task.getErrorMessage());
        assertEquals(logPath, task.getLogPath());
        verify(jobScheduler).releaseResources(task.getId(), task.getServerIp());
        verify(workloadClient).deleteTrainingJob(properties.getNamespace(), jobName);
        var cleanupOrder = inOrder(diagnosticService, workloadClient);
        cleanupOrder.verify(diagnosticService).archive(task, jobName);
        cleanupOrder.verify(workloadClient).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    @Test
    void startupFailureInsideGracePeriodKeepsTaskActive() {
        TrainingExperimentVersion task = activeTask("train-transient-pull", "running");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenReturn(Optional.of(new KubernetesWorkloadClient.TrainingJobStatus(
                        0,
                        0,
                        1,
                        "ErrImagePull",
                        "temporary registry failure",
                        Instant.now().minusSeconds(30)
                )));

        monitor.syncJobStatuses();

        assertEquals("running", task.getStatus());
        verify(repository, never()).save(task);
        verify(workloadClient, never()).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    @Test
    void containerCreatingIsNotTreatedAsFatal() {
        TrainingExperimentVersion task = activeTask("train-creating", "scheduled");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenReturn(Optional.of(new KubernetesWorkloadClient.TrainingJobStatus(
                        0,
                        0,
                        1,
                        "ContainerCreating",
                        null,
                        Instant.now().minusSeconds(600)
                )));

        monitor.syncJobStatuses();

        assertEquals("running", task.getStatus());
        verify(workloadClient, never()).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    @Test
    void stoppedTaskWinsRaceAgainstStartupFailureMonitor() {
        properties.setPodStartupFailureGraceSeconds(0);
        TrainingExperimentVersion observed = activeTask("train-stopped-race", "running");
        TrainingExperimentVersion current = activeTask("train-stopped-race", "stopped");
        String jobName = KubernetesJobNaming.jobNameForTraining(observed.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(observed));
        when(repository.findById(observed.getId())).thenReturn(Optional.of(current));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenReturn(Optional.of(new KubernetesWorkloadClient.TrainingJobStatus(
                        0,
                        0,
                        1,
                        "InvalidImageName",
                        null,
                        Instant.now().minusSeconds(600)
                )));

        monitor.syncJobStatuses();

        assertEquals("stopped", current.getStatus());
        verify(repository, never()).save(current);
        verify(diagnosticService, never()).archive(current, jobName);
        verify(workloadClient, never()).deleteTrainingJob(properties.getNamespace(), jobName);
        verify(jobScheduler, never()).releaseResources(observed.getId(), observed.getServerIp());
    }

    @Test
    void keepsStartupFailedJobWhenEvidenceCannotBeArchivedYet() {
        properties.setPodStartupFailureGraceSeconds(0);
        TrainingExperimentVersion task = activeTask("train-archive-retry", "running");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenReturn(Optional.of(new KubernetesWorkloadClient.TrainingJobStatus(
                        0,
                        0,
                        1,
                        "CreateContainerConfigError",
                        "missing secret",
                        Instant.now().minusSeconds(600)
                )));
        when(diagnosticService.archive(task, jobName))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(false, null));

        monitor.syncJobStatuses();

        assertEquals("failed", task.getStatus());
        verify(workloadClient, never()).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    @Test
    void deletesStartupFailedJobAfterDiagnosticArchiveRetrySucceeds() {
        TrainingExperimentVersion task = failedTask("train-archive-recovered");
        task.setErrorMessage("Pod 启动失败: ImagePullBackOff");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository
                .findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
                        any(),
                        any(Instant.class)
                ))
                .thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        String logPath = "minio://users/7/training-failure-diagnostics/train-archive-recovered/failure.log";
        when(diagnosticService.archive(task, jobName))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(true, logPath));

        monitor.syncJobStatuses();

        assertEquals(logPath, task.getLogPath());
        verify(workloadClient).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    @Test
    void apiReadFailureDoesNotChangeBusinessStatus() {
        TrainingExperimentVersion task = activeTask("train-api-unavailable", "running");
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task));
        when(workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName))
                .thenThrow(new KubernetesWorkloadException("API unavailable"));

        monitor.syncJobStatuses();

        assertEquals("running", task.getStatus());
        verify(repository, never()).save(task);
        verify(workloadClient, never()).deleteTrainingJob(properties.getNamespace(), jobName);
    }

    private TrainingExperimentVersion failedTask(String id) {
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setId(id);
        task.setOwnerUserId(7);
        task.setStatus("failed");
        task.setServerIp("k8s-node1");
        task.setFinishedAt(Instant.now().minusSeconds(60));
        return task;
    }

    private TrainingExperimentVersion activeTask(String id, String status) {
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setId(id);
        task.setOwnerUserId(7);
        task.setStatus(status);
        task.setServerIp("k8s-node1");
        task.setProgress(10);
        task.setCreatedAt(Instant.now().minusSeconds(600));
        task.setUpdatedAt(Instant.now().minusSeconds(600));
        return task;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
