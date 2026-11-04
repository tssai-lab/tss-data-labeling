package com.tss.platform.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingKubernetesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** Existing kubectl behavior behind the common training Job control surface. */
@Component
@ConditionalOnProperty(
        prefix = "training.kubernetes",
        name = "client-mode",
        havingValue = "kubectl",
        matchIfMissing = true
)
public class KubectlKubernetesWorkloadClient implements KubernetesWorkloadClient {

    private static final Logger LOG = LoggerFactory.getLogger(KubectlKubernetesWorkloadClient.class);

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner shellCommandRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KubectlKubernetesWorkloadClient(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            ShellCommandRunner shellCommandRunner
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.shellCommandRunner = shellCommandRunner;
        LOG.info("Training Kubernetes workload client selected: kubectl");
    }

    @Override
    public void applyTrainingJob(String namespace, String jobName, String jobYaml) {
        validateTarget(namespace, jobName);
        if (jobYaml == null || jobYaml.isBlank()) {
            throw new IllegalArgumentException("training Job manifest is empty");
        }

        Path kubeconfig = environmentService.resolveKubeconfig();
        List<String> command = environmentService.kubectlCommand(kubeconfig, "apply", "-f", "-");
        ShellCommandRunner.CommandResult result = shellCommandRunner.runWithInput(
                command,
                environmentService.resolveProjectRoot(),
                jobYaml,
                properties.getClientRequestTimeoutSeconds()
        );
        if (result.success()) {
            return;
        }

        // A timeout or concurrent dispatch can leave the first request successful on the
        // API server. Reconcile by deterministic Job name with the same client only.
        if (trainingJobExists(namespace, jobName)) {
            LOG.info("Training Job already exists after kubectl apply failure; treating it as submitted: job={}", jobName);
            return;
        }
        throw new KubernetesWorkloadException(
                "kubectl training Job submission failed: " + result.errorMessage() + outputSuffix(result.output())
        );
    }

    @Override
    public boolean trainingJobExists(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        Path kubeconfig = environmentService.resolveKubeconfig();
        List<String> command = environmentService.kubectlCommand(
                kubeconfig,
                "get", "job", jobName,
                "-n", namespace,
                "--ignore-not-found"
        );
        ShellCommandRunner.CommandResult result = shellCommandRunner.run(
                command,
                environmentService.resolveProjectRoot(),
                30
        );
        return result.success() && result.output() != null && !result.output().isBlank();
    }

    @Override
    public Optional<TrainingJobStatus> getTrainingJobStatus(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        Path kubeconfig = environmentService.resolveKubeconfig();
        Path projectRoot = environmentService.resolveProjectRoot();
        List<String> jobCommand = environmentService.kubectlCommand(
                kubeconfig,
                "get", "job", jobName,
                "-n", namespace,
                "--ignore-not-found",
                "-o", "jsonpath={.status.succeeded},{.status.failed},{.status.active}"
        );
        ShellCommandRunner.CommandResult jobResult = shellCommandRunner.run(jobCommand, projectRoot, 30);
        if (!jobResult.success()) {
            throw new KubernetesWorkloadException(
                    "kubectl training Job status check failed: "
                            + jobResult.errorMessage() + outputSuffix(jobResult.output())
            );
        }
        if (jobResult.output() == null || jobResult.output().isBlank()) {
            return Optional.empty();
        }

        int[] counters = parseCounters(jobResult.output());
        List<String> podCommand = environmentService.kubectlCommand(
                kubeconfig,
                "get", "pods",
                "-n", namespace,
                "-l", "job-name=" + jobName,
                "--sort-by=.metadata.creationTimestamp",
                "-o", "json"
        );
        ShellCommandRunner.CommandResult podResult = shellCommandRunner.run(podCommand, projectRoot, 30);
        if (!podResult.success()) {
            // Job counters remain authoritative. A temporary Pod-list permission or
            // API failure must not hide a terminal Job result.
            LOG.warn("Failed to read kubectl training Pod startup state: job={}, error={}",
                    jobName, podResult.errorMessage());
            return Optional.of(new TrainingJobStatus(
                    counters[0], counters[1], counters[2], null, null, null
            ));
        }
        PodStartupState podState;
        try {
            podState = parseNewestPodState(podResult.output());
        } catch (KubernetesWorkloadException exception) {
            LOG.warn("Failed to parse kubectl training Pod startup state: job={}, error={}",
                    jobName, exception.getMessage());
            podState = PodStartupState.empty();
        }
        return Optional.of(new TrainingJobStatus(
                counters[0],
                counters[1],
                counters[2],
                podState.waitingReason(),
                podState.waitingMessage(),
                podState.createdAt()
        ));
    }

    @Override
    public void deleteTrainingJob(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        Path kubeconfig = environmentService.resolveKubeconfig();
        List<String> command = environmentService.kubectlCommand(
                kubeconfig,
                "delete", "job", jobName,
                "-n", namespace,
                "--ignore-not-found"
        );
        ShellCommandRunner.CommandResult result = shellCommandRunner.run(
                command,
                environmentService.resolveProjectRoot(),
                60
        );
        if (!result.success()) {
            throw new KubernetesWorkloadException(
                    "kubectl training Job deletion failed: " + result.errorMessage() + outputSuffix(result.output())
            );
        }
    }

    private void validateTarget(String namespace, String jobName) {
        if (namespace == null || !namespace.equals(properties.getNamespace())) {
            throw new IllegalArgumentException("training Job namespace is not the configured namespace");
        }
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("training Job name is empty");
        }
    }

    private String outputSuffix(String output) {
        return output == null || output.isBlank() ? "" : "\n" + output;
    }

    private int[] parseCounters(String output) {
        String[] parts = output.trim().split(",", -1);
        return new int[]{parseInt(parts, 0), parseInt(parts, 1), parseInt(parts, 2)};
    }

    private int parseInt(String[] parts, int index) {
        if (parts.length <= index || parts[index] == null || parts[index].isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private PodStartupState parseNewestPodState(String output) {
        if (output == null || output.isBlank()) {
            return PodStartupState.empty();
        }
        try {
            JsonNode items = objectMapper.readTree(output).path("items");
            if (!items.isArray() || items.isEmpty()) {
                return PodStartupState.empty();
            }
            JsonNode pod = items.get(items.size() - 1);
            Instant createdAt = parseInstant(pod.path("metadata").path("creationTimestamp").asText(null));
            JsonNode status = pod.path("status");
            PodStartupState waiting = firstWaitingState(status.path("initContainerStatuses"), createdAt);
            if (waiting.waitingReason() != null) {
                return waiting;
            }
            waiting = firstWaitingState(status.path("containerStatuses"), createdAt);
            if (waiting.waitingReason() != null) {
                return waiting;
            }
            JsonNode conditions = status.path("conditions");
            if (conditions.isArray()) {
                for (JsonNode condition : conditions) {
                    if ("PodScheduled".equals(condition.path("type").asText())
                            && "False".equals(condition.path("status").asText())) {
                        return new PodStartupState(
                                condition.path("reason").asText("FailedScheduling"),
                                condition.path("message").asText(null),
                                createdAt
                        );
                    }
                }
            }
            return PodStartupState.empty();
        } catch (Exception exception) {
            throw new KubernetesWorkloadException("kubectl training Pod status output cannot be parsed", exception);
        }
    }

    private PodStartupState firstWaitingState(JsonNode statuses, Instant createdAt) {
        if (statuses.isArray()) {
            for (JsonNode status : statuses) {
                JsonNode waiting = status.path("state").path("waiting");
                String reason = waiting.path("reason").asText(null);
                if (reason != null && !reason.isBlank()) {
                    return new PodStartupState(reason, waiting.path("message").asText(null), createdAt);
                }
            }
        }
        return new PodStartupState(null, null, createdAt);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private record PodStartupState(String waitingReason, String waitingMessage, Instant createdAt) {
        private static PodStartupState empty() {
            return new PodStartupState(null, null, null);
        }
    }
}
