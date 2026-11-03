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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit, self-cleaning single-GPU smoke test for the reviewed internal cluster.
 *
 * <p>Two independent switches are required: the test must be enabled and an
 * operator must confirm that the selected physical GPU is idle. The image must
 * be immutable, the target node and API server must match exactly, one and only
 * one GPU is requested, and cleanup runs even when CUDA initialization fails.</p>
 */
@EnabledIfEnvironmentVariable(named = "TSS_REAL_K8S_GPU_SMOKE", matches = "true")
class Fabric8KubernetesRealGpuSmokeTest {

    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void trainsOneTinyTensorOnExactlyOneGpuAndCleansUp() {
        assertEquals("true", requiredEnvironment("TSS_REAL_K8S_GPU_IDLE_CONFIRMED"),
                "physical GPU idle state must be confirmed immediately before this test");
        Path kubeconfig = Path.of(requiredEnvironment("TSS_REAL_K8S_GPU_KUBECONFIG"))
                .toAbsolutePath()
                .normalize();
        String expectedApiServer = normalizeUrl(requiredEnvironment("TSS_REAL_K8S_GPU_API_SERVER"));
        String namespace = requiredEnvironment("TSS_REAL_K8S_GPU_NAMESPACE");
        String workerNode = requiredEnvironment("TSS_REAL_K8S_GPU_NODE_NAME");
        String image = requireImmutableImage(requiredEnvironment("TSS_REAL_K8S_GPU_IMAGE"));
        String serviceAccount = requiredEnvironment("TSS_REAL_K8S_GPU_SERVICE_ACCOUNT");
        String runtimeClass = requiredEnvironment("TSS_REAL_K8S_GPU_RUNTIME_CLASS");
        String jobName = "tss-fabric8-gpu-smoke-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

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
        KubernetesClient client = provider.getClient();

        try {
            assertEquals(expectedApiServer, normalizeUrl(client.getConfiguration().getMasterUrl()));
            Node node = client.nodes().withName(workerNode).get();
            assertNotNull(node, "reviewed GPU worker node is absent");
            assertTrue(isReady(node), "reviewed GPU worker node is not Ready");
            assertTrue(allocatableGpu(node) >= 1,
                    "reviewed node does not advertise an allocatable nvidia.com/gpu");

            workloadClient.applyTrainingJob(namespace, jobName,
                    jobYaml(namespace, jobName, workerNode, image, serviceAccount, runtimeClass));
            Pod completed = awaitCompletedPod(client, namespace, jobName);
            assertEquals(workerNode, completed.getSpec().getNodeName());
            assertEquals("1", completed.getSpec().getContainers().get(0)
                    .getResources().getLimits().get("nvidia.com/gpu").getAmount());
            String log = client.pods().inNamespace(namespace).withName(completed.getMetadata().getName()).getLog();
            assertTrue(log.contains("TSS_GPU_SMOKE_OK"), "GPU smoke success marker is absent: " + log);
        } finally {
            try {
                workloadClient.deleteTrainingJob(namespace, jobName);
                awaitPodsAbsent(client, namespace, jobName);
            } finally {
                provider.close();
            }
        }
    }

    private Pod awaitCompletedPod(KubernetesClient client, String namespace, String jobName) {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        Pod lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            List<Pod> pods = podsForJob(client, namespace, jobName);
            if (!pods.isEmpty()) {
                lastSeen = pods.get(0);
                String phase = lastSeen.getStatus() == null ? null : lastSeen.getStatus().getPhase();
                if ("Succeeded".equals(phase)) {
                    return lastSeen;
                }
                if ("Failed".equals(phase)) {
                    throw new AssertionError("single-GPU smoke Pod failed: " + statusSummary(lastSeen));
                }
            }
            sleepBriefly();
        }
        throw new AssertionError("single-GPU smoke Pod did not complete: " + statusSummary(lastSeen));
    }

    private void awaitPodsAbsent(KubernetesClient client, String namespace, String jobName) {
        Instant deadline = Instant.now().plus(DELETE_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (podsForJob(client, namespace, jobName).isEmpty()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("single-GPU smoke Pod remained after cleanup");
    }

    private List<Pod> podsForJob(KubernetesClient client, String namespace, String jobName) {
        return client.pods().inNamespace(namespace).withLabel("job-name", jobName).list().getItems();
    }

    private long allocatableGpu(Node node) {
        if (node.getStatus() == null || node.getStatus().getAllocatable() == null
                || node.getStatus().getAllocatable().get("nvidia.com/gpu") == null) {
            return 0;
        }
        return Long.parseLong(node.getStatus().getAllocatable().get("nvidia.com/gpu").getAmount());
    }

    private boolean isReady(Node node) {
        return node.getStatus() != null && node.getStatus().getConditions() != null
                && node.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType())
                        && "True".equals(condition.getStatus()));
    }

    private String requireImmutableImage(String image) {
        if (!image.matches("^[A-Za-z0-9._/:\\-]+@sha256:[a-f0-9]{64}$")) {
            throw new IllegalStateException("TSS_REAL_K8S_GPU_IMAGE must use an immutable sha256 digest");
        }
        return image;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required GPU smoke setting is absent: " + name);
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

    private String statusSummary(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return "no Pod observed";
        }
        return "phase=" + pod.getStatus().getPhase()
                + "; reason=" + pod.getStatus().getReason()
                + "; message=" + pod.getStatus().getMessage();
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GPU smoke test was interrupted", exception);
        }
    }

    private String jobYaml(
            String namespace,
            String jobName,
            String workerNode,
            String image,
            String serviceAccount,
            String runtimeClass
    ) {
        return """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: tss-fabric8-gpu-smoke
                spec:
                  backoffLimit: 0
                  activeDeadlineSeconds: 120
                  ttlSecondsAfterFinished: 60
                  template:
                    metadata:
                      labels:
                        app.kubernetes.io/managed-by: tss-fabric8-gpu-smoke
                    spec:
                      runtimeClassName: %s
                      nodeName: %s
                      serviceAccountName: %s
                      automountServiceAccountToken: false
                      restartPolicy: Never
                      terminationGracePeriodSeconds: 2
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 10001
                        runAsGroup: 10001
                        seccompProfile:
                          type: RuntimeDefault
                      volumes:
                        - name: tmp
                          emptyDir:
                            sizeLimit: 64Mi
                      containers:
                        - name: single-gpu-smoke
                          image: %s
                          imagePullPolicy: IfNotPresent
                          command: ["python", "-c"]
                          args:
                            - |
                              import json, torch
                              assert torch.cuda.is_available(), "CUDA is unavailable"
                              assert torch.cuda.device_count() == 1, "expected exactly one visible GPU"
                              torch.manual_seed(20260827)
                              device = torch.device("cuda:0")
                              model = torch.nn.Linear(8, 2).to(device)
                              optimizer = torch.optim.SGD(model.parameters(), lr=0.05)
                              features = torch.randn(32, 8, device=device)
                              labels = torch.randint(0, 2, (32,), device=device)
                              optimizer.zero_grad(set_to_none=True)
                              loss = torch.nn.functional.cross_entropy(model(features), labels)
                              loss.backward()
                              optimizer.step()
                              torch.cuda.synchronize()
                              print("TSS_GPU_SMOKE_OK " + json.dumps({
                                  "torch": torch.__version__, "cuda": torch.version.cuda,
                                  "device": torch.cuda.get_device_name(0), "loss": float(loss)
                              }, sort_keys=True), flush=True)
                          env:
                            - name: HOME
                              value: /tmp
                            - name: PYTHONDONTWRITEBYTECODE
                              value: "1"
                          volumeMounts:
                            - name: tmp
                              mountPath: /tmp
                          securityContext:
                            allowPrivilegeEscalation: false
                            readOnlyRootFilesystem: true
                            capabilities:
                              drop: ["ALL"]
                          resources:
                            requests:
                              cpu: 500m
                              memory: 1Gi
                              nvidia.com/gpu: "1"
                            limits:
                              cpu: "1"
                              memory: 2Gi
                              nvidia.com/gpu: "1"
                """.formatted(jobName, namespace, runtimeClass, workerNode, serviceAccount, image);
    }
}
