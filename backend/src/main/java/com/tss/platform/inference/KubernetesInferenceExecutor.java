package com.tss.platform.inference;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.service.InferenceTaskService;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.slf4j.Logger;
import com.tss.platform.service.JobScheduler;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class KubernetesInferenceExecutor implements InferenceExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesInferenceExecutor.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("success", "failed", "stopped");
    private static final Set<String> BINDABLE_STATUSES = Set.of("pending", "queued", "scheduled");

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final InferenceTaskRepository taskRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final InferenceScriptVersionRepository scriptVersionRepository;
    private final KubernetesInferenceJobManifestBuilder manifestBuilder;
    private final TransactionTemplate transactionTemplate;
    private JobScheduler jobScheduler;
    private ComputeServerRepository computeServerRepository;
    private InferenceModelCacheProperties modelCacheProperties = new InferenceModelCacheProperties();


    @Value("${minio.access-key:}")
    private String minioAccessKey;

    @Value("${minio.secret-key:}")
    private String minioSecretKey;

    @Value("${minio.bucket:models}")
    private String minioBucket;

    public KubernetesInferenceExecutor(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            InferenceTaskRepository taskRepository,
            ModelVersionRepository modelVersionRepository,
            DatasetVersionRepository datasetVersionRepository,
            InferenceScriptVersionRepository scriptVersionRepository,
            KubernetesInferenceJobManifestBuilder manifestBuilder,
            PlatformTransactionManager transactionManager
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.taskRepository = taskRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.scriptVersionRepository = scriptVersionRepository;
        this.manifestBuilder = manifestBuilder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    void setNodeScheduling(
            JobScheduler jobScheduler,
            ComputeServerRepository computeServerRepository,
            InferenceModelCacheProperties modelCacheProperties
    ) {
        this.jobScheduler = jobScheduler;
        this.computeServerRepository = computeServerRepository;
        this.modelCacheProperties = modelCacheProperties;
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && environmentService.isKubernetesReady();
    }

    @Override
    public String getType() {
        return "kubernetes";
    }

    @Override
    public void start(String taskId) {
        Integer attempt = taskRepository.findById(taskId)
                .map(InferenceTask::getCurrentAttempt)
                .orElse(1);
        start(taskId, attempt);
    }

    public void start(String taskId, Integer attempt) {
        int safeAttempt = Math.max(attempt == null ? 1 : attempt, 1);
        Thread thread = new Thread(
                () -> submitJob(taskId, safeAttempt),
                "k8s-inference-submit-" + taskId + "-a" + safeAttempt
        );
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop(String taskId) {
        Integer attempt = taskRepository.findById(taskId)
                .map(InferenceTask::getCurrentAttempt)
                .orElse(1);
        stop(taskId, attempt);
    }

    public void stop(String taskId, Integer attempt) {
        String jobName = KubernetesInferenceJobNaming.jobNameForInference(taskId, attempt);
        Path kubeconfig = environmentService.resolveKubeconfig();
        List<String> deleteCmd = environmentService.kubectlCommand(
                kubeconfig,
                "delete", "job", jobName,
                "-n", properties.getNamespace(),
                "--ignore-not-found"
        );
        ShellCommandRunner.CommandResult result = runWithStdin(
                deleteCmd,
                environmentService.resolveProjectRoot(),
                "",
                60
        );
        if (!result.success()) {
            LOG.warn("删除 K8s 推理 Job 失败: taskId={}, error={}", taskId, result.errorMessage());
        }
    }

    void submitJob(String taskId, Integer attempt) {
        updateStatus(taskId, attempt, "queued", 0, null);
        try {
            InferenceTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("推理任务不存在: " + taskId));
            int currentAttempt = Math.max(task.getCurrentAttempt() == null ? 1 : task.getCurrentAttempt(), 1);
            if (currentAttempt != attempt || TERMINAL_STATUSES.contains(task.getStatus())) {
                LOG.info("Skip stale inference submission: taskId={}, expectedAttempt={}, currentAttempt={}, status={}",
                        taskId, attempt, currentAttempt, task.getStatus());
                return;
            }
            ModelVersion modelVersion = modelVersionRepository.findByIdAndDeletedFalse(task.getModelVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("模型版本不存在: " + task.getModelVersionId()));
            InferenceScriptVersion scriptVersion = scriptVersionRepository.findByIdAndDeletedFalse(task.getScriptVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("推理脚本版本不存在: " + task.getScriptVersionId()));
            DatasetVersion datasetVersion = null;
            if (InferenceTaskService.INPUT_MODE_DATASET_VERSION.equals(task.getInputMode())) {
                datasetVersion = datasetVersionRepository.findByIdAndDeletedFalse(task.getDatasetVersionId())
                        .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在: " + task.getDatasetVersionId()));
            }
            if (jobScheduler == null || computeServerRepository == null) {
                throw new IllegalStateException("inference node scheduling is not configured");
            }
            String assignedServerIp;
            synchronized (jobScheduler) {
                assignedServerIp = jobScheduler.assignNodeForInference(
                        task,
                        modelVersion.getArtifactAttestedSha256()
                );
                if (assignedServerIp == null || assignedServerIp.isBlank()) {
                    String reason = modelCacheProperties.isEnabled()
                            ? "no enabled cache-ready node has enough resources for inference"
                            : "no enabled online node has enough resources for inference";
                    throw new IllegalStateException(reason);
                }
                if (!bindInferenceNode(taskId, attempt, assignedServerIp)) {
                    LOG.info("Skip stale inference node binding: taskId={}, attempt={}", taskId, attempt);
                    return;
                }
            }
            String targetNodeName = resolveNodeName(assignedServerIp);


            String yaml = manifestBuilder.buildJobYaml(
                    task,
                    modelVersion,
                    scriptVersion,
                    datasetVersion,
                    minioAccessKey,
                    minioSecretKey,
                    minioBucket,
                    targetNodeName
            );

            Path kubeconfig = environmentService.resolveKubeconfig();
            List<String> applyCmd = environmentService.kubectlCommand(kubeconfig, "apply", "-f", "-");
            ShellCommandRunner.CommandResult result = runWithStdin(
                    applyCmd,
                    environmentService.resolveProjectRoot(),
                    yaml,
                    120
            );
            if (!result.success()) {
                throw new IllegalStateException("提交 K8s 推理 Job 失败: " + result.errorMessage() + "\n" + result.output());
            }
            LOG.info("K8s 推理 Job 已提交: taskId={}, job={}",
                    taskId, KubernetesInferenceJobNaming.jobNameForInference(taskId, task.getCurrentAttempt()));
        } catch (Exception e) {
            LOG.error("K8s 推理 Job 提交失败: taskId={}, attempt={}", taskId, attempt, e);
            updateStatus(taskId, attempt, "failed", 0, e.getMessage());
        }
    }

    ShellCommandRunner.CommandResult runWithStdin(
            List<String> command,
            Path workingDirectory,
            String stdinContent,
            int timeoutSeconds
    ) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            Process startedProcess = process;
            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(stdinContent.getBytes(StandardCharsets.UTF_8));
            }
            StringBuffer output = new StringBuffer();
            Thread outputReader = new Thread(() -> {
                try (var reader = startedProcess.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to drain kubectl output: {}", e.getMessage());
                }
            }, "k8s-inference-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outputReader.join(1_000);
                return ShellCommandRunner.CommandResult.failed(-1, output.toString(), "kubectl apply 超时");
            }
            outputReader.join(5_000);
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ShellCommandRunner.CommandResult.success(output.toString());
            }
            return ShellCommandRunner.CommandResult.failed(exitCode, output.toString(), "kubectl apply 失败 exit=" + exitCode);
        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return ShellCommandRunner.CommandResult.failed(-1, "", "kubectl apply interrupted");
        } catch (Exception e) {
            return ShellCommandRunner.CommandResult.failed(-1, "", e.getMessage());
        }
    }

    String resolveNodeName(String serverIp) {
        return computeServerRepository.findByServerIpAndDeletedFalse(serverIp)
                .map(ComputeServer::getK8sNodeName)
                .filter(name -> !name.isBlank())
                .orElse(serverIp);
    }

    boolean bindInferenceNode(String taskId, Integer attempt, String serverIp) {
        Boolean bound = transactionTemplate.execute(tx -> taskRepository.findByIdForUpdate(taskId)
                .map(task -> {
                    int currentAttempt = Math.max(
                            task.getCurrentAttempt() == null ? 1 : task.getCurrentAttempt(),
                            1
                    );
                    if (currentAttempt != attempt || !BINDABLE_STATUSES.contains(task.getStatus())) {
                        return false;
                    }
                    if (task.getServerIp() != null && !task.getServerIp().isBlank()) {
                        return false;
                    }
                    task.setServerIp(serverIp);
                    task.setStatus("scheduled");
                    if (task.getGpuUuid() != null && !task.getGpuUuid().isBlank()) {
                        task.setErrorMessage("等待所选物理 GPU 空闲；平台不会自动换卡");
                    }
                    task.setUpdatedAt(Instant.now());
                    taskRepository.save(task);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(bound);
    }

    private void updateStatus(
            String taskId,
            Integer attempt,
            String status,
            int progress,
            String errorMessage
    ) {
        transactionTemplate.executeWithoutResult(tx -> taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            int currentAttempt = Math.max(task.getCurrentAttempt() == null ? 1 : task.getCurrentAttempt(), 1);
            if (currentAttempt != attempt || TERMINAL_STATUSES.contains(task.getStatus())) {
                return;
            }
            // scheduled 状态的任务已分配到节点，不要降级回 queued
            if ("queued".equals(status) && "scheduled".equals(task.getStatus())) {
                return;
            }
            task.setStatus(status);
            task.setProgress(progress);
            task.setUpdatedAt(Instant.now());
            if (errorMessage != null) {
                task.setErrorMessage(errorMessage);
                task.setFinishedAt(Instant.now());
            }
            taskRepository.save(task);
        }));
    }
}
