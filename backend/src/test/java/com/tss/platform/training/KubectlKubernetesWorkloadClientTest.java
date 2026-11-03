package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubectlKubernetesWorkloadClientTest {

    private static final String NAMESPACE = "tss-training";
    private static final String JOB_NAME = "training-42";
    private static final String YAML = "apiVersion: batch/v1\nkind: Job\n";

    private final TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
    private final TrainingEnvironmentService environmentService = mock(TrainingEnvironmentService.class);
    private final ShellCommandRunner shellCommandRunner = mock(ShellCommandRunner.class);
    private final Path kubeconfig = Path.of("test-kubeconfig");
    private final Path projectRoot = Path.of("project-root");
    private final List<String> applyCommand = List.of("kubectl", "apply");
    private final List<String> getCommand = List.of("kubectl", "get");
    private final List<String> deleteCommand = List.of("kubectl", "delete");
    private final List<String> statusCommand = List.of("kubectl", "job-status");
    private final List<String> podStatusCommand = List.of("kubectl", "pod-status");

    private KubectlKubernetesWorkloadClient client;

    @BeforeEach
    void setUp() {
        properties.setNamespace(NAMESPACE);
        properties.setClientRequestTimeoutSeconds(37);
        when(environmentService.resolveKubeconfig()).thenReturn(kubeconfig);
        when(environmentService.resolveProjectRoot()).thenReturn(projectRoot);
        when(environmentService.kubectlCommand(kubeconfig, "apply", "-f", "-"))
                .thenReturn(applyCommand);
        when(environmentService.kubectlCommand(
                kubeconfig, "get", "job", JOB_NAME, "-n", NAMESPACE, "--ignore-not-found"))
                .thenReturn(getCommand);
        when(environmentService.kubectlCommand(
                kubeconfig, "delete", "job", JOB_NAME, "-n", NAMESPACE, "--ignore-not-found"))
                .thenReturn(deleteCommand);
        when(environmentService.kubectlCommand(
                kubeconfig,
                "get", "job", JOB_NAME,
                "-n", NAMESPACE,
                "--ignore-not-found",
                "-o", "jsonpath={.status.succeeded},{.status.failed},{.status.active}"))
                .thenReturn(statusCommand);
        when(environmentService.kubectlCommand(
                kubeconfig,
                "get", "pods",
                "-n", NAMESPACE,
                "-l", "job-name=" + JOB_NAME,
                "--sort-by=.metadata.creationTimestamp",
                "-o", "json"))
                .thenReturn(podStatusCommand);
        client = new KubectlKubernetesWorkloadClient(properties, environmentService, shellCommandRunner);
    }

    @Test
    void passesManifestUnchangedAndUsesConfiguredTimeout() {
        when(shellCommandRunner.runWithInput(applyCommand, projectRoot, YAML, 37))
                .thenReturn(ShellCommandRunner.CommandResult.success("created"));

        client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML);

        verify(shellCommandRunner).runWithInput(applyCommand, projectRoot, YAML, 37);
        verify(shellCommandRunner, never()).run(getCommand, projectRoot, 30);
    }

    @Test
    void treatsFailedApplyAsSubmittedOnlyWhenTheSameJobExists() {
        when(shellCommandRunner.runWithInput(applyCommand, projectRoot, YAML, 37))
                .thenReturn(ShellCommandRunner.CommandResult.failed(-1, "timeout", "timed out"));
        when(shellCommandRunner.run(getCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success("job.batch/" + JOB_NAME));

        assertDoesNotThrow(() -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML));
    }

    @Test
    void reportsFailedApplyWhenTheJobCannotBeConfirmed() {
        when(shellCommandRunner.runWithInput(applyCommand, projectRoot, YAML, 37))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "denied", "apply failed"));
        when(shellCommandRunner.run(getCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(""));

        assertThrows(
                KubernetesWorkloadException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
    }

    @Test
    void rejectsAnotherNamespaceBeforeCallingKubectl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client.applyTrainingJob("other", JOB_NAME, YAML)
        );

        verify(shellCommandRunner, never()).runWithInput(applyCommand, projectRoot, YAML, 37);
    }

    @Test
    void deleteUsesIgnoreNotFoundAndReportsTimeout() {
        when(shellCommandRunner.run(deleteCommand, projectRoot, 60))
                .thenReturn(ShellCommandRunner.CommandResult.failed(-1, "", "timed out"));

        assertThrows(
                KubernetesWorkloadException.class,
                () -> client.deleteTrainingJob(NAMESPACE, JOB_NAME)
        );
        verify(shellCommandRunner).run(deleteCommand, projectRoot, 60);
    }

    @Test
    void parsesJobCountersAndInitContainerWaitingState() {
        String podJson = """
                {"items":[{"metadata":{"creationTimestamp":"2026-09-02T12:00:00Z"},
                "status":{"initContainerStatuses":[{"state":{"waiting":{
                "reason":"CreateContainerConfigError","message":"secret not found"}}}]}}]}
                """;
        when(shellCommandRunner.run(statusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(",,1"));
        when(shellCommandRunner.run(podStatusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(podJson));

        var status = client.getTrainingJobStatus(NAMESPACE, JOB_NAME);

        assertTrue(status.isPresent());
        assertEquals(1, status.orElseThrow().active());
        assertEquals("CreateContainerConfigError", status.orElseThrow().podWaitingReason());
        assertEquals("secret not found", status.orElseThrow().podWaitingMessage());
        assertEquals(Instant.parse("2026-09-02T12:00:00Z"), status.orElseThrow().podCreatedAt());
    }

    @Test
    void exposesFailedSchedulingInsteadOfCallingAPendingPodRunning() {
        String podJson = """
                {"items":[{"metadata":{"creationTimestamp":"2026-09-02T12:00:00Z"},
                "status":{"conditions":[{"type":"PodScheduled","status":"False",
                "reason":"FailedScheduling","message":"selected ResourceClaim is not available"}]}}]}
                """;
        when(shellCommandRunner.run(statusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(",,1"));
        when(shellCommandRunner.run(podStatusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(podJson));

        var status = client.getTrainingJobStatus(NAMESPACE, JOB_NAME).orElseThrow();

        assertEquals("FailedScheduling", status.podWaitingReason());
        assertEquals("selected ResourceClaim is not available", status.podWaitingMessage());
    }

    @Test
    void returnsEmptyOnlyWhenJobIsAbsent() {
        when(shellCommandRunner.run(statusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success(""));

        assertTrue(client.getTrainingJobStatus(NAMESPACE, JOB_NAME).isEmpty());

        verify(shellCommandRunner, never()).run(podStatusCommand, projectRoot, 30);
    }

    @Test
    void doesNotHideKubernetesApiFailureAsMissingJob() {
        when(shellCommandRunner.run(statusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "forbidden", "denied"));

        assertThrows(
                KubernetesWorkloadException.class,
                () -> client.getTrainingJobStatus(NAMESPACE, JOB_NAME)
        );
    }

    @Test
    void podListFailureDoesNotHideJobCounters() {
        when(shellCommandRunner.run(statusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.success("1,,"));
        when(shellCommandRunner.run(podStatusCommand, projectRoot, 30))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "forbidden", "denied"));

        var status = client.getTrainingJobStatus(NAMESPACE, JOB_NAME);

        assertTrue(status.isPresent());
        assertEquals(1, status.orElseThrow().succeeded());
        assertEquals(null, status.orElseThrow().podWaitingReason());
    }
}
