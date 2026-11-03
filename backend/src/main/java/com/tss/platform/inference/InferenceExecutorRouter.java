package com.tss.platform.inference;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.training.TrainingEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 推理提交与恢复入口；通过数据库条件更新领取某次 attempt，防止重复提交和旧请求覆盖重试。
 * 当前推理只路由到 Kubernetes 执行器；环境不可用时记录失败，不静默改成本地推理。
 */
@Service
public class InferenceExecutorRouter implements InferenceExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(InferenceExecutorRouter.class);
    private static final Duration STALE_SUBMISSION_AGE = Duration.ofMinutes(5);
    private static final Set<String> TERMINAL_STATUSES = Set.of("success", "failed", "stopped");
    private static final List<String> RECOVERABLE_STATUSES = List.of("pending", "queued", "scheduled");

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final KubernetesInferenceExecutor kubernetesInferenceExecutor;
    private final InferenceTaskRepository taskRepository;
    private final TransactionTemplate transactionTemplate;

    public InferenceExecutorRouter(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            KubernetesInferenceExecutor kubernetesInferenceExecutor,
            InferenceTaskRepository taskRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.kubernetesInferenceExecutor = kubernetesInferenceExecutor;
        this.taskRepository = taskRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && environmentService.isKubernetesReady();
    }

    @Override
    public String getType() {
        return "router";
    }

    @Override
    public void start(String taskId) {
        Integer attempt = taskRepository.findById(taskId)
                .map(InferenceTask::getCurrentAttempt)
                .orElse(null);
        if (attempt == null) {
            LOG.debug("Skip missing inference task submission: taskId={}", taskId);
            return;
        }
        start(taskId, attempt);
    }

    /** 先提交领取状态，再调用外部 Kubernetes；远程调用不占用数据库长事务。 */
    public void start(String taskId, Integer attempt) {
        int safeAttempt = Math.max(attempt == null ? 1 : attempt, 1);
        Integer claimed = transactionTemplate.execute(tx ->
                taskRepository.claimForSubmission(taskId, safeAttempt, Instant.now()));
        if (claimed == null || claimed != 1) {
            LOG.debug("Skip duplicate or stale inference submission: taskId={}, attempt={}", taskId, safeAttempt);
            return;
        }

        try {
            if (!isAvailable()) {
                LOG.warn("K8s inference environment unavailable: taskId={}, attempt={}", taskId, safeAttempt);
                markFailed(taskId, safeAttempt, "K8s 推理环境不可用，无法执行推理任务");
                return;
            }
            LOG.info("Start inference: taskId={}, attempt={}, executor={}",
                    taskId, safeAttempt, kubernetesInferenceExecutor.getType());
            kubernetesInferenceExecutor.start(taskId, safeAttempt);
        } catch (RuntimeException e) {
            LOG.error("Failed to start inference submission: taskId={}, attempt={}", taskId, safeAttempt, e);
            markFailed(taskId, safeAttempt, e.getMessage());
        }
    }

    @Override
    public void stop(String taskId) {
        Integer attempt = taskRepository.findById(taskId)
                .map(InferenceTask::getCurrentAttempt)
                .orElse(1);
        stop(taskId, attempt);
    }

    public void stop(String taskId, Integer attempt) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            kubernetesInferenceExecutor.stop(taskId, attempt);
        } catch (RuntimeException e) {
            LOG.warn("Failed to stop inference job: taskId={}, attempt={}, error={}",
                    taskId, attempt, e.getMessage());
        }
    }

    /** 恢复长时间未提交的记录；沿用原 attempt，由执行器核对该次任务，不新建业务重试。 */
    @Scheduled(fixedDelayString = "${tss.inference.recovery-delay-ms:30000}")
    public void recoverStaleSubmissions() {
        Instant staleBefore = Instant.now().minus(STALE_SUBMISSION_AGE);
        List<InferenceTask> staleTasks = taskRepository
                .findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        RECOVERABLE_STATUSES,
                        staleBefore
                );
        for (InferenceTask snapshot : staleTasks) {
            int attempt = Math.max(snapshot.getCurrentAttempt() == null ? 1 : snapshot.getCurrentAttempt(), 1);
            if ("scheduled".equals(snapshot.getStatus())) {
                Integer reset = transactionTemplate.execute(tx -> taskRepository.resetStaleSubmission(
                        snapshot.getId(),
                        attempt,
                        staleBefore,
                        Instant.now()
                ));
                if (reset == null || reset != 1) {
                    continue;
                }
            }
            LOG.warn("Recover stale inference submission: taskId={}, attempt={}, status={}",
                    snapshot.getId(), attempt, snapshot.getStatus());
            start(snapshot.getId(), attempt);
        }
    }

    /** 只更新仍属于本次 attempt 的非终态记录，防止延迟失败覆盖已成功/停止的新状态。 */
    private void markFailed(String taskId, Integer attempt, String message) {
        transactionTemplate.executeWithoutResult(tx -> taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            int currentAttempt = Math.max(task.getCurrentAttempt() == null ? 1 : task.getCurrentAttempt(), 1);
            if (currentAttempt != attempt || TERMINAL_STATUSES.contains(task.getStatus())) {
                return;
            }
            task.setStatus("failed");
            task.setProgress(0);
            task.setErrorMessage(message);
            task.setFinishedAt(Instant.now());
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
        }));
    }
}
