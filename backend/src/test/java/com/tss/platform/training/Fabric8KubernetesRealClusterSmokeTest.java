package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit, self-cleaning smoke test against the reviewed internal cluster.
 *
 * <p>The test is skipped unless an operator deliberately sets
 * {@code TSS_REAL_K8S_SMOKE=true}. It never requests a GPU, never uses a default
 * kubeconfig, validates the exact API server and worker node, and removes its
 * uniquely named Job in a finally block.</p>
 */
@EnabledIfEnvironmentVariable(named = "TSS_REAL_K8S_SMOKE", matches = "true")
class Fabric8KubernetesRealClusterSmokeTest {

    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration POD_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void createsReconcilesSchedulesAndDeletesOneCpuOnlyJob() {
        Path kubeconfig = Path.of(requiredEnvironment("TSS_REAL_K8S_KUBECONFIG"))
                .toAbsolutePath()
                .normalize();
        String expectedApiServer = normalizeUrl(requiredEnvironment("TSS_REAL_K8S_API_SERVER"));
        String namespace = requiredEnvironment("TSS_REAL_K8S_NAMESPACE");
        String workerNode = requiredEnvironment("TSS_REAL_K8S_NODE_NAME");
        String image = requiredEnvironment("TSS_REAL_K8S_IMAGE");
        String serviceAccount = requiredEnvironment("TSS_REAL_K8S_SERVICE_ACCOUNT");
        String jobName = "tss-fabric8-smoke-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setClientMode(TrainingKubernetesProperties.ClientMode.FABRIC8);
        properties.setNamespace(namespace);
        properties.setKubeconfig(kubeconfig.toString());
        properties.setClientRequestTimeoutSeconds(15);

        TrainingEnvironmentService environmentService = mock(TrainingEnvironmentService.class);
        when(environmentService.resolveKubeconfig()).thenReturn(kubeconfig);
        Fabric8KubernetesClientProvider provider =
                new Fabric8KubernetesClientProvider(properties, environmentService);
        Fabric8KubernetesWorkloadClient workloadClient =
                new Fabric8KubernetesWorkloadClient(properties, provider);

        KubernetesClient kubernetesClient = provider.getClient();
        assertEquals(expectedApiServer, normalizeUrl(kubernetesClient.getConfiguration().getMasterUrl()));
        Node targetNode = kubernetesClient.nodes().withName(workerNode).get();
        assertNotNull(targetNode, "reviewed worker node is absent from the selected cluster");
        assertTrue(isReady(targetNode), "reviewed worker node is not Ready");

        String yaml = jobYaml(namespace, jobName, workerNode, image, serviceAccount);
        try {
            assertFalse(workloadClient.trainingJobExists(namespace, jobName));
            workloadClient.applyTrainingJob(namespace, jobName, yaml);
            await(CREATE_TIMEOUT, () -> workloadClient.trainingJobExists(namespace, jobName),
                    "Fabric8-created Job did not become visible");

            // The second create must reconcile the deterministic name with Fabric8 only.
            workloadClient.applyTrainingJob(namespace, jobName, yaml);

            Pod runningPod = awaitRunningPod(kubernetesClient, namespace, jobName, POD_TIMEOUT);
            assertEquals(workerNode, runningPod.getSpec().getNodeName());
            assertFalse(hasGpuRequest(runningPod), "smoke Pod unexpectedly requests a GPU");

            workloadClient.deleteTrainingJob(namespace, jobName);
            await(DELETE_TIMEOUT, () -> !workloadClient.trainingJobExists(namespace, jobName),
                    "Fabric8-deleted Job is still visible");
            await(DELETE_TIMEOUT, () -> podsForJob(kubernetesClient, namespace, jobName).isEmpty(),
                    "Pod remained after its smoke Job was deleted");

            // Deleting an already absent Job is intentionally idempotent.
            workloadClient.deleteTrainingJob(namespace, jobName);
        } finally {
            kubernetesClient.batch().v1().jobs().inNamespace(namespace).withName(jobName).delete();
            try {
                await(DELETE_TIMEOUT, () -> podsForJob(kubernetesClient, namespace, jobName).isEmpty(),
                        "cleanup could not remove the smoke Pod");
            } finally {
                provider.close();
            }
        }
    }

    private Pod awaitRunningPod(
            KubernetesClient client,
            String namespace,
            String jobName,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        Pod lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            List<Pod> pods = podsForJob(client, namespace, jobName);
            if (!pods.isEmpty()) {
                lastSeen = pods.get(0);
                if (lastSeen.getStatus() != null && "Running".equals(lastSeen.getStatus().getPhase())) {
                    return lastSeen;
                }
                if (lastSeen.getStatus() != null && "Failed".equals(lastSeen.getStatus().getPhase())) {
                    throw new AssertionError("smoke Pod entered Failed phase: " + statusSummary(lastSeen));
                }
            }
            sleepBriefly();
        }
        throw new AssertionError("smoke Pod did not reach Running: " + statusSummary(lastSeen));
    }

    private List<Pod> podsForJob(KubernetesClient client, String namespace, String jobName) {
        return client.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list()
                .getItems();
    }

    private boolean hasGpuRequest(Pod pod) {
        return pod.getSpec().getContainers().stream().anyMatch(container -> {
            if (container.getResources() == null) {
                return false;
            }
            return container.getResources().getRequests().containsKey("nvidia.com/gpu")
                    || container.getResources().getLimits().containsKey("nvidia.com/gpu");
        });
    }

    private boolean isReady(Node node) {
        return node.getStatus() != null
                && node.getStatus().getConditions() != null
                && node.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    private void await(Duration timeout, BooleanSupplier condition, String failureMessage) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError(failureMessage);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("smoke test was interrupted", exception);
        }
    }

    private String statusSummary(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return "no Pod observed";
        }
        StringBuilder summary = new StringBuilder("phase=").append(pod.getStatus().getPhase());
        if (pod.getStatus().getConditions() != null) {
            pod.getStatus().getConditions().forEach(condition -> summary
                    .append("; ")
                    .append(condition.getType())
                    .append('=')
                    .append(condition.getStatus())
                    .append('(')
                    .append(condition.getReason())
                    .append(')'));
        }
        return summary.toString();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required real-cluster smoke setting is absent: " + name);
        }
        return value.trim();
    }

    private String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String jobYaml(
            String namespace,
            String jobName,
            String workerNode,
            String image,
            String serviceAccount
    ) {
        return """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: tss-fabric8-smoke
                spec:
                  backoffLimit: 0
                  activeDeadlineSeconds: 120
                  ttlSecondsAfterFinished: 60
                  template:
                    metadata:
                      labels:
                        app.kubernetes.io/managed-by: tss-fabric8-smoke
                    spec:
                      nodeName: %s
                      serviceAccountName: %s
                      automountServiceAccountToken: false
                      restartPolicy: Never
                      terminationGracePeriodSeconds: 1
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 65532
                        runAsGroup: 65532
                        seccompProfile:
                          type: RuntimeDefault
                      containers:
                        - name: hold
                          image: %s
                          imagePullPolicy: IfNotPresent
                          securityContext:
                            allowPrivilegeEscalation: false
                            readOnlyRootFilesystem: true
                            capabilities:
                              drop: ["ALL"]
                          resources:
                            requests:
                              cpu: 100m
                              memory: 128Mi
                            limits:
                              cpu: 100m
                              memory: 128Mi
                """.formatted(jobName, namespace, workerNode, serviceAccount, image);
    }
}
