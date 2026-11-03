package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;

/** Submits one immutable RunSpec as one Kubernetes Job/Pod. */
@Component
public class KubernetesTrainingExecutor implements TrainingExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesTrainingExecutor.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("success", "failed", "stopped", "cancelled");

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final TrainingExperimentVersionRepository repository;
    private final KubernetesJobManifestBuilder manifestBuilder;
    private final KubernetesWorkloadClient workloadClient;
    private final TransactionTemplate transactionTemplate;
    private ComputeServerRepository computeServerRepository;

    @Value("${minio.access-key:}")
    private String minioAccessKey;

    @Value("${minio.secret-key:}")
    private String minioSecretKey;

    @Value("${minio.bucket:models}")
    private String minioBucket;

    public KubernetesTrainingExecutor(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            TrainingExperimentVersionRepository repository,
            KubernetesJobManifestBuilder manifestBuilder,
            KubernetesWorkloadClient workloadClient,
            TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.repository = repository;
        this.manifestBuilder = manifestBuilder;
        this.workloadClient = workloadClient;
        this.transactionTemplate = transactionTemplate;
    }
    @Autowired
    void setComputeServerRepository(ComputeServerRepository computeServerRepository) {
        this.computeServerRepository = computeServerRepository;
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
    public void start(String trainingId) {
        Thread thread = new Thread(() -> submitJob(trainingId), "k8s-training-submit-" + trainingId);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop(String trainingId) {
        String jobName = KubernetesJobNaming.jobNameForTraining(trainingId);
        try {
            workloadClient.deleteTrainingJob(properties.getNamespace(), jobName);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to delete K8s Job: trainingId={}, error={}", trainingId, exception.getMessage());
            throw exception;
        }
    }

    void submitJob(String trainingId) {
        try {
            // 1. 根据训练ID查数据库拿到任务实体
            TrainingExperimentVersion task = repository.findById(trainingId)
                    .orElseThrow(() -> new IllegalArgumentException("training task does not exist: " + trainingId));
            if (task.getStatus() != null && TERMINAL_STATUSES.contains(task.getStatus())) return;

            // 2. 获取要调度到的K8s节点IP，即JobScheduler绑定出来的节点
            String targetNode = resolveNodeName(task.getServerIp());

            // 重点：构建K8s Job完整YAML清单
            String yaml = manifestBuilder.buildJobYaml(task, minioAccessKey, minioSecretKey, minioBucket, targetNode);

            String jobName = KubernetesJobNaming.jobNameForTraining(trainingId);
            workloadClient.applyTrainingJob(properties.getNamespace(), jobName, yaml);

            // 保持 scheduled 状态，由 KubernetesTrainingJobMonitor 根据 Job 状态接管（running/success/failed）
            LOG.info("K8s training Job submitted: trainingId={}, plan={}, job={}",
                    trainingId, task.getTrainingPlanId(), jobName);
        } catch (Exception e) {
            LOG.error("K8s training Job submission failed: trainingId={}", trainingId, e);
            updateStatus(trainingId, "failed", 0, e.getMessage());
        }
    }

    String resolveNodeName(String serverIp) {
        if (serverIp == null || serverIp.isBlank() || computeServerRepository == null) {
            return serverIp;
        }
        return computeServerRepository.findByServerIpAndDeletedFalse(serverIp)
                .map(ComputeServer::getK8sNodeName)
                .filter(name -> !name.isBlank())
                .orElse(serverIp);
    }

    private void updateStatus(String trainingId, String status, int progress, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> repository.findById(trainingId).ifPresent(version -> {
            if (TERMINAL_STATUSES.contains(version.getStatus())) {
                return;
            }
            // scheduled 状态的任务已分配到节点，不要降级回 queued
            if ("queued".equals(status) && "scheduled".equals(version.getStatus())) {
                return;
            }
            version.setStatus(status);
            version.setProgress(progress);
            version.setUpdatedAt(Instant.now());
            if (errorMessage != null) {
                version.setErrorMessage(errorMessage);
                version.setFinishedAt(Instant.now());
            }
            repository.save(version);
        }));
    }
}
