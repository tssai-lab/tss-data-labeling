package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.service.JobScheduler;
import com.tss.platform.service.TrainingModelPublishService;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingRunSpecCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component
public class KubernetesTrainingJobMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesTrainingJobMonitor.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("success", "failed", "stopped", "cancelled");
    private static final Set<String> FATAL_POD_STARTUP_REASONS = Set.of(
            "ImagePullBackOff",
            "ErrImagePull",
            "InvalidImageName",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError"
    );
    private static final String POD_STARTUP_FAILURE_PREFIX = "Pod 启动失败: ";

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final TrainingExperimentVersionRepository repository;
    private final KubernetesWorkloadClient workloadClient;
    private final TrainingRunSpecCodec runSpecCodec;
    private final TransactionTemplate transactionTemplate;
    private final JobScheduler jobScheduler;
    private final TrainingFailureDiagnosticService failureDiagnosticService;
    private final Clock clock;
    private final ConcurrentMap<String, StartupFailureObservation> startupFailureObservations =
            new ConcurrentHashMap<>();

    @Autowired
    public KubernetesTrainingJobMonitor(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            TrainingExperimentVersionRepository repository,
            KubernetesWorkloadClient workloadClient,
            TrainingRunSpecCodec runSpecCodec,
            TransactionTemplate transactionTemplate,
            @Lazy JobScheduler jobScheduler,
            TrainingFailureDiagnosticService failureDiagnosticService
    ) {
        this(
                properties,
                environmentService,
                repository,
                workloadClient,
                runSpecCodec,
                transactionTemplate,
                jobScheduler,
                failureDiagnosticService,
                Clock.systemUTC()
        );
    }

    KubernetesTrainingJobMonitor(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            TrainingExperimentVersionRepository repository,
            KubernetesWorkloadClient workloadClient,
            TrainingRunSpecCodec runSpecCodec,
            TransactionTemplate transactionTemplate,
            @Lazy JobScheduler jobScheduler,
            TrainingFailureDiagnosticService failureDiagnosticService,
            Clock clock
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.repository = repository;
        this.workloadClient = workloadClient;
        this.runSpecCodec = runSpecCodec;
        this.transactionTemplate = transactionTemplate;
        this.jobScheduler = jobScheduler;
        this.failureDiagnosticService = failureDiagnosticService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${training.kubernetes.monitor-interval-ms:30000}")
    public void syncJobStatuses() {
        if (!properties.isEnabled() || !environmentService.isKubernetesReady()) {
            return;
        }
        List<TrainingExperimentVersion> activeTasks = repository.findAllByOrderByCreatedAtDesc()
                .stream()
                // 只监控已提交 Job 的任务（scheduled=已分配节点待提交、running=运行中）。
                // queued（排队等节点）还没有 Job，由 JobScheduler.dispatchQueuedTasks 处理，不在这里监控。
                .filter(task -> "scheduled".equals(task.getStatus()) || "running".equals(task.getStatus()))
                .toList();
        Set<String> activeJobNames = activeTasks.stream()
                .map(task -> KubernetesJobNaming.jobNameForTraining(task.getId()))
                .collect(Collectors.toSet());
        startupFailureObservations.keySet().removeIf(jobName -> !activeJobNames.contains(jobName));

        for (TrainingExperimentVersion task : activeTasks) {
            syncSingleTask(task);
        }
        archiveRecentFailures();
    }

    private void syncSingleTask(TrainingExperimentVersion task) {
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        Optional<KubernetesWorkloadClient.TrainingJobStatus> statusResult;
        try {
            statusResult = workloadClient.getTrainingJobStatus(properties.getNamespace(), jobName);
        } catch (RuntimeException exception) {
            startupFailureObservations.remove(jobName);
            LOG.warn("Failed to read K8s training Job status: id={}, error={}",
                    task.getId(), exception.getMessage());
            return;
        }
        if (statusResult.isEmpty()) {
            startupFailureObservations.remove(jobName);
            // Job 不存在：scheduled 任务的 Job 可能还没提交（submitJob 在异步线程执行），
            // 不标记失败，等下一轮轮询再查。
            return;
        }

        KubernetesWorkloadClient.TrainingJobStatus status = statusResult.get();

        if (status.succeeded() > 0) {
            startupFailureObservations.remove(jobName);
            markSucceeded(task.getId());
            return;
        }
        if (status.failed() > 0) {
            startupFailureObservations.remove(jobName);
            String podError = status.podWaitingReason() != null
                    ? "Pod 状态: " + status.podWaitingReason()
                    : "K8s Job 执行失败";
            markFailed(task.getId(), podError);
            archiveFailure(task.getId(), jobName);
            return;
        }
        if (isExpiredFatalStartupFailure(jobName, status)) {
            startupFailureObservations.remove(jobName);
            String errorMessage = startupFailureMessage(status);
            if (markFailed(task.getId(), errorMessage)) {
                boolean evidenceReady = !properties.isFailureDiagnosticsEnabled()
                        || archiveFailure(task.getId(), jobName);
                if (evidenceReady) {
                    deleteStartupFailedJob(jobName, task.getId());
                }
            }
            return;
        }
        if ("FailedScheduling".equals(status.podWaitingReason())) {
            markWaitingForSelectedResource(task.getId(), status.podWaitingMessage());
            return;
        }
        if (status.active() > 0 && !"running".equals(task.getStatus())) {
            markRunning(task.getId());
        }
    }

    private boolean isExpiredFatalStartupFailure(
            String jobName,
            KubernetesWorkloadClient.TrainingJobStatus status
    ) {
        if (!FATAL_POD_STARTUP_REASONS.contains(status.podWaitingReason())) {
            startupFailureObservations.remove(jobName);
            return false;
        }
        Instant now = clock.instant();
        StartupFailureObservation observation = startupFailureObservations.compute(
                jobName,
                (ignored, current) -> current == null
                        || !Objects.equals(current.podCreatedAt(), status.podCreatedAt())
                        ? new StartupFailureObservation(status.podCreatedAt(), now)
                        : current
        );
        int graceSeconds = Math.max(0, properties.getPodStartupFailureGraceSeconds());
        return !now.isBefore(observation.firstObservedAt().plusSeconds(graceSeconds));
    }

    private String startupFailureMessage(KubernetesWorkloadClient.TrainingJobStatus status) {
        StringBuilder message = new StringBuilder(POD_STARTUP_FAILURE_PREFIX)
                .append(status.podWaitingReason());
        if (status.podWaitingMessage() != null && !status.podWaitingMessage().isBlank()) {
            String detail = TrainingFailureDiagnosticService.redact(status.podWaitingMessage())
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!detail.isBlank()) {
                message.append(" - ").append(detail);
            }
        }
        if (message.length() > 1000) {
            return message.substring(0, 1000);
        }
        return message.toString();
    }

    private void deleteStartupFailedJob(String jobName, String trainingId) {
        try {
            workloadClient.deleteTrainingJob(properties.getNamespace(), jobName);
            LOG.info("Deleted startup-failed K8s Job after preserving diagnostics: id={}, job={}",
                    trainingId, jobName);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to delete startup-failed K8s Job: id={}, job={}, error={}",
                    trainingId, jobName, exception.getMessage());
        }
    }

    private void archiveRecentFailures() {
        if (!properties.isFailureDiagnosticsEnabled()) {
            return;
        }
        int retryWindowSeconds = Math.max(60, properties.getFailureDiagnosticsRetryWindowSeconds());
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(retryWindowSeconds));
        List<TrainingExperimentVersion> candidates = repository
                .findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
                        "failed",
                        cutoff
                );
        for (TrainingExperimentVersion candidate : candidates) {
            String jobName = KubernetesJobNaming.jobNameForTraining(candidate.getId());
            boolean archived = archiveFailure(candidate.getId(), jobName);
            if (archived && isStartupFailure(candidate.getErrorMessage())) {
                deleteStartupFailedJob(jobName, candidate.getId());
            }
        }
    }

    private boolean archiveFailure(String trainingId, String jobName) {
        TrainingExperimentVersion task = null;
        TrainingFailureDiagnosticService.CaptureResult capture = null;
        try {
            task = repository.findById(trainingId).orElse(null);
            if (task == null || !"failed".equals(task.getStatus())
                    || (task.getLogPath() != null && !task.getLogPath().isBlank())) {
                return task != null && task.getLogPath() != null && !task.getLogPath().isBlank();
            }
            capture = failureDiagnosticService.archive(task, jobName);
            if (!capture.archived() || capture.logPath() == null) {
                return false;
            }
            TrainingFailureDiagnosticService.CaptureResult archivedCapture = capture;
            Boolean attached = transactionTemplate.execute(tx -> repository.findById(trainingId)
                    .map(current -> {
                        if (archivedCapture.logPath().equals(current.getLogPath())) {
                            return true;
                        }
                        if (!"failed".equals(current.getStatus())
                                || (current.getLogPath() != null && !current.getLogPath().isBlank())) {
                            return false;
                        }
                        current.setLogPath(archivedCapture.logPath());
                        current.setUpdatedAt(Instant.now());
                        repository.save(current);
                        return true;
                    })
                    .orElse(false));
            if (!Boolean.TRUE.equals(attached)) {
                failureDiagnosticService.enqueueDeletion(task, capture.logPath());
            }
            return Boolean.TRUE.equals(attached);
        } catch (Exception exception) {
            if (task != null && capture != null && capture.archived() && capture.logPath() != null) {
                try {
                    failureDiagnosticService.enqueueDeletion(task, capture.logPath());
                } catch (Exception cleanupException) {
                    LOG.warn("Failed to enqueue unattached K8s diagnostics: id={}, error={}",
                            trainingId, cleanupException.getMessage());
                }
            }
            LOG.warn("Failed to archive K8s training diagnostics: id={}, error={}",
                    trainingId, exception.getMessage());
            return false;
        }
    }

    private boolean isStartupFailure(String errorMessage) {
        return errorMessage != null && errorMessage.startsWith(POD_STARTUP_FAILURE_PREFIX);
    }

    private record StartupFailureObservation(Instant podCreatedAt, Instant firstObservedAt) {
    }

    private void markRunning(String trainingId) {
        transactionTemplate.executeWithoutResult(tx -> repository.findById(trainingId).ifPresent(version -> {
            if (TERMINAL_STATUSES.contains(version.getStatus())) {
                return;
            }
            version.setStatus("running");
            version.setProgress(Math.max(version.getProgress() != null ? version.getProgress() : 0, 10));
            if (version.getStartedAt() == null) {
                version.setStartedAt(Instant.now());
            }
            version.setUpdatedAt(Instant.now());
            if (version.getErrorMessage() != null
                    && version.getErrorMessage().startsWith("等待所选资源")) {
                version.setErrorMessage(null);
            }
            repository.save(version);
        }));
    }

    private void markWaitingForSelectedResource(String trainingId, String detail) {
        transactionTemplate.executeWithoutResult(tx -> repository.findById(trainingId).ifPresent(version -> {
            if (TERMINAL_STATUSES.contains(version.getStatus())) return;
            String suffix = detail == null || detail.isBlank()
                    ? ""
                    : "：" + TrainingFailureDiagnosticService.redact(detail)
                            .replaceAll("\\s+", " ").trim();
            String message = "等待所选资源" + suffix;
            version.setErrorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            version.setUpdatedAt(Instant.now());
            repository.save(version);
        }));
    }

    private void markSucceeded(String trainingId) {
        transactionTemplate.executeWithoutResult(tx -> repository.findById(trainingId).ifPresent(version -> {
            if (TERMINAL_STATUSES.contains(version.getStatus())) {
                return;
            }
            version.setStatus("success");
            version.setProgress(100);
            version.setFinishedAt(Instant.now());
            if (version.getErrorMessage() != null
                    && version.getErrorMessage().startsWith("等待所选资源")) {
                version.setErrorMessage(null);
            }
            if (version.getProducedModelVersionId() == null
                    && version.getModelPublishStatus() == null) {
                if (version.getRunSpecJson() != null && !version.getRunSpecJson().isBlank()) {
                    String modelPath = runSpecCodec.decode(version).outputs().artifacts().stream()
                            .filter(artifact -> Boolean.TRUE.equals(artifact.publishAsModel()))
                            .map(TrainingPlanDefinition.Artifact::path)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("RunSpec has no publishable model artifact"));
                    version.setModelArtifactPath(
                            "training-results/" + version.getId() + "/artifacts/" + modelPath
                    );
                    version.setModelPublishStatus(TrainingModelPublishService.STATUS_PENDING);
                }
            }
            version.setUpdatedAt(Instant.now());
            repository.save(version);
            String nodeName = version.getServerIp();
            if (nodeName != null) {
                jobScheduler.releaseResources(trainingId, nodeName);
            }
            LOG.info("训练任务因 K8s Job 成功而同步为 success: id={}", trainingId);
        }));
    }

    private boolean markFailed(String trainingId, String errorMessage) {
        Boolean updated = transactionTemplate.execute(tx -> repository.findById(trainingId)
                .map(version -> {
                    if (TERMINAL_STATUSES.contains(version.getStatus())) {
                        return false;
                    }
                    version.setStatus("failed");
                    version.setProgress(version.getProgress() == null ? 0 : version.getProgress());
                    version.setErrorMessage(errorMessage);
                    version.setFinishedAt(Instant.now());
                    version.setUpdatedAt(Instant.now());
                    repository.save(version);
                    String nodeName = version.getServerIp();
                    if (nodeName != null) {
                        jobScheduler.releaseResources(trainingId, nodeName);
                    }
                    LOG.warn("训练任务同步为 failed: id={}, reason={}", trainingId, errorMessage);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(updated);
    }
}
