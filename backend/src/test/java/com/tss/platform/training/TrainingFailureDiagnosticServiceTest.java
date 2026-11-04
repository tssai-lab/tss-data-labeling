package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.service.MinioDeleteTaskService;
import com.tss.platform.service.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingFailureDiagnosticServiceTest {

    private TrainingKubernetesProperties properties;
    private TrainingEnvironmentService environmentService;
    private ShellCommandRunner shellCommandRunner;
    private MinioService minioService;
    private TrainingExperimentVersionRepository repository;
    private MinioDeleteTaskService deleteTaskService;
    private TransactionTemplate transactionTemplate;
    private TrainingFailureDiagnosticService service;

    @BeforeEach
    void setUp() {
        properties = new TrainingKubernetesProperties();
        properties.setNamespace("tss-training");
        properties.setFailureDiagnosticsEnabled(true);
        properties.setFailureDiagnosticsMaxBytes(4_096);
        environmentService = mock(TrainingEnvironmentService.class);
        shellCommandRunner = mock(ShellCommandRunner.class);
        minioService = mock(MinioService.class);
        repository = mock(TrainingExperimentVersionRepository.class);
        deleteTaskService = mock(MinioDeleteTaskService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        service = new TrainingFailureDiagnosticService(
                properties,
                environmentService,
                shellCommandRunner,
                minioService,
                repository,
                deleteTaskService,
                transactionTemplate
        );

        when(environmentService.resolveKubeconfig()).thenReturn(Path.of("kubeconfig"));
        when(environmentService.resolveProjectRoot()).thenReturn(Path.of("project"));
        when(environmentService.kubectlCommand(any(Path.class), any(String[].class)))
                .thenAnswer(invocation -> {
                    Object[] arguments = invocation.getArguments();
                    if (arguments.length == 2 && arguments[1] instanceof String[] values) {
                        return Arrays.asList(values);
                    }
                    return Arrays.stream(arguments)
                            .skip(1)
                            .map(String.class::cast)
                            .toList();
                });
    }

    @Test
    void archivesOnlySafeBoundedFailureEvidence() throws Exception {
        TrainingExperimentVersion task = failedTask("train-1", 7);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> objectName = new AtomicReference<>();
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenAnswer(invocation -> commandResult(invocation.getArgument(0)));
        doAnswer(invocation -> {
            objectName.set(invocation.getArgument(0));
            uploaded.set(invocation.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(minioService).uploadStream(any(), any(InputStream.class), anyLong(), any());

        TrainingFailureDiagnosticService.CaptureResult result = service.archive(task, "tss-train-train-1");

        assertTrue(result.archived());
        assertEquals("minio://users/7/training-failure-diagnostics/train-1/failure.log", result.logPath());
        assertEquals("users/7/training-failure-diagnostics/train-1/failure.log", objectName.get());
        assertTrue(uploaded.get().length <= 4_096);
        String report = new String(uploaded.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains("POD_PHASE=Failed"));
        assertTrue(report.contains("INIT_TERMINATED_REASON=Error"));
        assertTrue(report.contains("[REDACTED]"));
        assertTrue(report.contains("[TRUNCATED]"));
        assertFalse(report.contains("stage-c-password"));
        assertFalse(report.contains("stage-c-signature"));
        verify(shellCommandRunner, never()).run(
                org.mockito.ArgumentMatchers.argThat(command -> command.contains("describe")),
                any(Path.class),
                anyInt()
        );
        verify(shellCommandRunner, never()).run(
                org.mockito.ArgumentMatchers.argThat(command -> command.stream()
                        .anyMatch(argument -> argument.startsWith("--limit="))),
                any(Path.class),
                anyInt()
        );
        verify(shellCommandRunner, atLeastOnce()).run(
                org.mockito.ArgumentMatchers.argThat(command -> command.stream()
                        .anyMatch(argument -> argument.contains(".items[*]"))),
                any(Path.class),
                anyInt()
        );
        verify(shellCommandRunner, never()).run(
                org.mockito.ArgumentMatchers.argThat(command -> command.stream()
                        .anyMatch(argument -> argument.matches(".*\\.items\\[[0-9]+:[0-9]+].*"))),
                any(Path.class),
                anyInt()
        );
        verify(shellCommandRunner, atLeastOnce()).run(
                org.mockito.ArgumentMatchers.argThat(command -> command.contains("pod-2")),
                any(Path.class),
                anyInt()
        );
        verify(shellCommandRunner, never()).run(
                org.mockito.ArgumentMatchers.argThat(command -> {
                    int outputIndex = command.indexOf("-o");
                    return outputIndex >= 0 && command.size() > outputIndex + 1
                            && "json".equals(command.get(outputIndex + 1));
                }),
                any(Path.class),
                anyInt()
        );
    }

    @Test
    void runningPodWithoutFailureEvidenceIsRetriedLater() throws Exception {
        TrainingExperimentVersion task = failedTask("train-2", 7);
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (containsSequence(command, "get", "job")) {
                        return ShellCommandRunner.CommandResult.success("JOB_ACTIVE=1\nJOB_FAILED=0\n");
                    }
                    if (containsSequence(command, "get", "pods")) {
                        return ShellCommandRunner.CommandResult.success("pod-1\n");
                    }
                    if (containsSequence(command, "get", "pod")) {
                        return ShellCommandRunner.CommandResult.success(
                                "POD_PHASE=Running\nCONTAINER_NAME=worker\nWAITING_REASON=ContainerCreating\n"
                                        + "TERMINATED_REASON=\nEXIT_CODE=\n"
                        );
                    }
                    return ShellCommandRunner.CommandResult.success("");
                });

        TrainingFailureDiagnosticService.CaptureResult result = service.archive(task, "tss-train-train-2");

        assertFalse(result.archived());
        verify(minioService, never()).uploadStream(any(), any(), anyLong(), any());
    }

    @Test
    void stopSnapshotIsArchivedWhileThePodIsStillRunning() throws Exception {
        TrainingExperimentVersion task = failedTask("train-stop", 7);
        task.setStatus("running");
        task.setErrorMessage(null);
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (containsSequence(command, "get", "job")) {
                        return ShellCommandRunner.CommandResult.success("JOB_ACTIVE=1\nJOB_FAILED=0\n");
                    }
                    if (containsSequence(command, "get", "pods")) {
                        return ShellCommandRunner.CommandResult.success("pod-1\n");
                    }
                    if (containsSequence(command, "get", "pod")) {
                        return ShellCommandRunner.CommandResult.success(
                                "POD_PHASE=Running\nCONTAINER_NAME=worker\n"
                        );
                    }
                    return ShellCommandRunner.CommandResult.success("last training line\n");
                });

        TrainingFailureDiagnosticService.CaptureResult result = service.archiveBeforeStop(
                task,
                "tss-train-train-stop"
        );

        assertTrue(result.archived());
        verify(minioService).uploadStream(any(), any(InputStream.class), anyLong(), any());
    }

    @Test
    void databaseFailureIsArchivedAfterTheWorkloadDisappears() throws Exception {
        TrainingExperimentVersion task = failedTask("train-no-workload", 7);
        task.setServerIp(null);
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success(""));

        TrainingFailureDiagnosticService.CaptureResult result = service.archive(
                task,
                "tss-train-train-no-workload"
        );

        assertTrue(result.archived());
        verify(minioService).uploadStream(any(), any(InputStream.class), anyLong(), any());
    }

    @Test
    void imagePullBackOffIsArchivedAsFailureEvidence() throws Exception {
        TrainingExperimentVersion task = failedTask("train-image-pull", 7);
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (containsSequence(command, "get", "job")) {
                        return ShellCommandRunner.CommandResult.success("JOB_ACTIVE=1\nJOB_FAILED=0\n");
                    }
                    if (containsSequence(command, "get", "pods")) {
                        return ShellCommandRunner.CommandResult.success("pod-1\n");
                    }
                    if (containsSequence(command, "get", "pod")) {
                        return ShellCommandRunner.CommandResult.success(
                                "POD_PHASE=Pending\nCONTAINER_NAME=worker\n"
                                        + "WAITING_REASON=ImagePullBackOff\nEXIT_CODE=\n"
                        );
                    }
                    return ShellCommandRunner.CommandResult.success("");
                });

        TrainingFailureDiagnosticService.CaptureResult result = service.archive(
                task,
                "tss-train-train-image-pull"
        );

        assertTrue(result.archived());
        verify(minioService).uploadStream(any(), any(InputStream.class), anyLong(), any());
    }

    @Test
    void minioFailureLeavesTaskRetryableAndNextAttemptUsesSameObject() throws Exception {
        TrainingExperimentVersion task = failedTask("train-retry", 7);
        when(shellCommandRunner.run(anyList(), any(Path.class), anyInt()))
                .thenAnswer(invocation -> commandResult(invocation.getArgument(0)));
        doThrow(new Exception("temporary MinIO failure"))
                .doNothing()
                .when(minioService).uploadStream(any(), any(InputStream.class), anyLong(), any());

        TrainingFailureDiagnosticService.CaptureResult first = service.archive(task, "tss-train-train-retry");
        TrainingFailureDiagnosticService.CaptureResult second = service.archive(task, "tss-train-train-retry");

        assertFalse(first.archived());
        assertTrue(second.archived());
        assertEquals(
                "minio://users/7/training-failure-diagnostics/train-retry/failure.log",
                second.logPath()
        );
        verify(minioService, times(2)).uploadStream(
                eq("users/7/training-failure-diagnostics/train-retry/failure.log"),
                any(InputStream.class),
                anyLong(),
                any()
        );
    }

    @Test
    void cleanupQueuesExactOwnedObjectAndClearsDatabasePath() {
        TrainingExperimentVersion task = failedTask("train-3", 7);
        task.setFinishedAt(Instant.now().minusSeconds(31L * 24 * 60 * 60));
        task.setLogPath("minio://users/7/training-failure-diagnostics/train-3/failure.log");
        when(repository.findExpiredFailureDiagnostics(any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(List.of(task));
        when(repository.findById(task.getId())).thenReturn(Optional.of(task));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.cleanupExpiredDiagnostics();

        verify(deleteTaskService).enqueueDefaultBucketDelete(
                "users/7/training-failure-diagnostics/train-3/failure.log",
                MinioDeleteTaskService.SOURCE_TRAINING_FAILURE_DIAGNOSTIC,
                "train-3",
                7
        );
        assertEquals(null, task.getLogPath());
        verify(repository).save(task);
    }

    @Test
    void deletionNeverCrossesTaskOwnerOrIdBoundary() {
        TrainingExperimentVersion task = failedTask("train-4", 7);

        service.enqueueDeletion(
                task,
                "minio://users/99/training-failure-diagnostics/train-4/failure.log"
        );
        service.enqueueDeletion(
                task,
                "minio://users/7/training-failure-diagnostics/another-task/failure.log"
        );

        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(any(), any(), any(), any());
    }

    @Test
    void redactionCoversCredentialsSignedUrlsAndPrivateKeys() {
        String raw = "password=stage-c-password "
                + "quoted_password=\"stage-c password with spaces\"\n"
                + "Authorization: Basic stage-c-token\n"
                + "https://user:stage-c-uri@example.test/path?X-Amz-Signature=stage-c-signature\n"
                + "-----BEGIN PRIVATE KEY-----\nstage-c-private\n-----END PRIVATE KEY-----";

        String redacted = TrainingFailureDiagnosticService.redact(raw);

        assertFalse(redacted.contains("stage-c-password"));
        assertFalse(redacted.contains("stage-c password with spaces"));
        assertFalse(redacted.contains("stage-c-token"));
        assertFalse(redacted.contains("stage-c-uri"));
        assertFalse(redacted.contains("stage-c-signature"));
        assertFalse(redacted.contains("stage-c-private"));
    }

    private ShellCommandRunner.CommandResult commandResult(List<String> command) {
        if (containsSequence(command, "get", "job")) {
            return ShellCommandRunner.CommandResult.success(
                    "JOB_NAME=tss-train-train-1\nJOB_FAILED=1\nJOB_CONDITION_TYPE=Failed\n"
                            + "JOB_CONDITION_STATUS=True\nJOB_CONDITION_REASON=BackoffLimitExceeded\n"
            );
        }
        if (containsSequence(command, "get", "pods")) {
            return ShellCommandRunner.CommandResult.success("pod-1\npod-2\n");
        }
        if (containsSequence(command, "get", "pod")) {
            return ShellCommandRunner.CommandResult.success(
                    "POD_PHASE=Failed\nINIT_CONTAINER_NAME=init-download\n"
                            + "INIT_TERMINATED_REASON=Error\nINIT_EXIT_CODE=1\n"
                            + "CONTAINER_NAME=worker\nTERMINATED_REASON=OOMKilled\n"
                            + "EXIT_CODE=137\nIMAGE=worker:test\n"
            );
        }
        if (containsSequence(command, "get", "events")) {
            return ShellCommandRunner.CommandResult.success(
                    "EVENT_REASON=BackOff\nEVENT_MESSAGE=restart failed\n"
            );
        }
        if (command.contains("logs")) {
            return ShellCommandRunner.CommandResult.success(
                    ("password=stage-c-password "
                            + "url=https://example.test/a?X-Amz-Signature=stage-c-signature\n")
                            .repeat(200)
            );
        }
        return ShellCommandRunner.CommandResult.failed(1, "", "unexpected command");
    }

    private boolean containsSequence(List<String> command, String first, String second) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if (first.equals(command.get(index)) && second.equals(command.get(index + 1))) {
                return true;
            }
        }
        return false;
    }

    private TrainingExperimentVersion failedTask(String id, int ownerUserId) {
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setId(id);
        task.setExperimentId("exp-1");
        task.setVersionNo(1);
        task.setOwnerUserId(ownerUserId);
        task.setStatus("failed");
        task.setErrorMessage("worker failed");
        task.setServerIp("k8s-node1");
        task.setFinishedAt(Instant.now().minusSeconds(60));
        return task;
    }
}
