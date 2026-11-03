package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 受限训练 Job 控制接口的 Fabric8 实现，与 kubectl 实现通过配置互斥装配。 */
@Component
@ConditionalOnProperty(prefix = "training.kubernetes", name = "client-mode", havingValue = "fabric8")
public class Fabric8KubernetesWorkloadClient implements KubernetesWorkloadClient {

    private static final Logger LOG = LoggerFactory.getLogger(Fabric8KubernetesWorkloadClient.class);

    private final TrainingKubernetesProperties properties;
    private final Fabric8KubernetesClientProvider clientProvider;

    public Fabric8KubernetesWorkloadClient(
            TrainingKubernetesProperties properties,
            Fabric8KubernetesClientProvider clientProvider
    ) {
        this.properties = properties;
        this.clientProvider = clientProvider;
        LOG.info("Training Kubernetes workload client selected: fabric8");
    }

    @Override
    public void applyTrainingJob(String namespace, String jobName, String jobYaml) {
        Job job = parseSingleTrainingJob(namespace, jobName, jobYaml);
        try {
            client().batch().v1().jobs().inNamespace(namespace).resource(job).create();
        } catch (RuntimeException exception) {
            // 不在这里调用 kubectl：请求可能已被 API 接收。
            // 使用同一个 Fabric8 客户端和确定的 Job 名称核对结果，避免重复创建。
            try {
                if (trainingJobExists(namespace, jobName)) {
                    LOG.info("Training Job already exists after Fabric8 create failure; treating it as submitted: job={}", jobName);
                    return;
                }
            } catch (RuntimeException reconciliationFailure) {
                exception.addSuppressed(reconciliationFailure);
            }
            throw new KubernetesWorkloadException("Fabric8 training Job submission failed", exception);
        }
    }

    @Override
    public boolean trainingJobExists(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        try {
            return client().batch().v1().jobs().inNamespace(namespace).withName(jobName).get() != null;
        } catch (RuntimeException exception) {
            throw new KubernetesWorkloadException("Fabric8 training Job existence check failed", exception);
        }
    }

    @Override
    public Optional<TrainingJobStatus> getTrainingJobStatus(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        try {
            Job job = client().batch().v1().jobs().inNamespace(namespace).withName(jobName).get();
            if (job == null) {
                return Optional.empty();
            }

            Pod newestPod = null;
            try {
                PodList podList = client().pods()
                        .inNamespace(namespace)
                        .withLabel("job-name", jobName)
                        .list();
                newestPod = podList == null || podList.getItems() == null
                        ? null
                        : podList.getItems().stream()
                                .max(Comparator.comparing(this::podCreatedAtOrEpoch))
                                .orElse(null);
            } catch (RuntimeException podException) {
                // Job 计数是终态依据；临时无法列出 Pod 不能掩盖 Job 已成功/失败的事实。
                LOG.warn("Failed to read Fabric8 training Pod startup state: job={}, error={}",
                        jobName, podException.getMessage());
            }
            ContainerStateWaiting waiting = waitingState(newestPod).orElse(null);

            return Optional.of(new TrainingJobStatus(
                    valueOrZero(job.getStatus() != null ? job.getStatus().getSucceeded() : null),
                    valueOrZero(job.getStatus() != null ? job.getStatus().getFailed() : null),
                    valueOrZero(job.getStatus() != null ? job.getStatus().getActive() : null),
                    waiting != null ? waiting.getReason() : null,
                    waiting != null ? waiting.getMessage() : null,
                    newestPod != null ? podCreatedAtOrNull(newestPod) : null
            ));
        } catch (RuntimeException exception) {
            throw new KubernetesWorkloadException("Fabric8 training Job status check failed", exception);
        }
    }

    @Override
    public void deleteTrainingJob(String namespace, String jobName) {
        validateTarget(namespace, jobName);
        try {
            client().batch().v1().jobs().inNamespace(namespace).withName(jobName).delete();
        } catch (RuntimeException exception) {
            try {
                if (!trainingJobExists(namespace, jobName)) {
                    LOG.info("Training Job is absent after Fabric8 delete failure; treating it as deleted: job={}", jobName);
                    return;
                }
            } catch (RuntimeException reconciliationFailure) {
                exception.addSuppressed(reconciliationFailure);
            }
            throw new KubernetesWorkloadException("Fabric8 training Job deletion failed", exception);
        }
    }

    private Job parseSingleTrainingJob(String namespace, String expectedJobName, String jobYaml) {
        validateTarget(namespace, expectedJobName);
        if (jobYaml == null || jobYaml.isBlank()) {
            throw new IllegalArgumentException("training Job manifest is empty");
        }

        KubernetesClient configuredClient = client();
        List<HasMetadata> resources;
        try (ByteArrayInputStream input = new ByteArrayInputStream(jobYaml.getBytes(StandardCharsets.UTF_8))) {
            resources = configuredClient.load(input).items();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("training Job manifest cannot be parsed", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("training Job manifest input cannot be closed", exception);
        }
        if (resources == null || resources.size() != 1 || !(resources.get(0) instanceof Job job)) {
            throw new IllegalArgumentException("training manifest must contain exactly one batch/v1 Job");
        }
        if (!"batch/v1".equals(job.getApiVersion()) || !"Job".equals(job.getKind())) {
            throw new IllegalArgumentException("training manifest must contain exactly one batch/v1 Job");
        }
        if (job.getMetadata() == null
                || job.getMetadata().getName() == null
                || job.getMetadata().getName().isBlank()) {
            throw new IllegalArgumentException("training Job manifest has no metadata.name");
        }
        if (!expectedJobName.equals(job.getMetadata().getName())) {
            throw new IllegalArgumentException("training Job manifest name does not match the expected Job name");
        }
        String manifestNamespace = job.getMetadata().getNamespace();
        if (manifestNamespace != null && !manifestNamespace.isBlank() && !namespace.equals(manifestNamespace)) {
            throw new IllegalArgumentException("training Job manifest namespace does not match configured namespace");
        }
        job.getMetadata().setNamespace(namespace);
        return job;
    }

    private KubernetesClient client() {
        return clientProvider.getClient();
    }

    private Optional<ContainerStateWaiting> waitingState(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return Optional.empty();
        }
        Stream<ContainerStatus> initStatuses = pod.getStatus().getInitContainerStatuses() == null
                ? Stream.empty()
                : pod.getStatus().getInitContainerStatuses().stream();
        Stream<ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses() == null
                ? Stream.empty()
                : pod.getStatus().getContainerStatuses().stream();
        return Stream.concat(initStatuses, containerStatuses)
                .filter(status -> status != null && status.getState() != null
                        && status.getState().getWaiting() != null)
                .map(status -> status.getState().getWaiting())
                .filter(waiting -> waiting.getReason() != null && !waiting.getReason().isBlank())
                .findFirst();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private Instant podCreatedAtOrEpoch(Pod pod) {
        Instant createdAt = podCreatedAtOrNull(pod);
        return createdAt == null ? Instant.EPOCH : createdAt;
    }

    private Instant podCreatedAtOrNull(Pod pod) {
        if (pod == null || pod.getMetadata() == null
                || pod.getMetadata().getCreationTimestamp() == null) {
            return null;
        }
        try {
            return Instant.parse(pod.getMetadata().getCreationTimestamp());
        } catch (DateTimeParseException ignored) {
            return null;
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
}
