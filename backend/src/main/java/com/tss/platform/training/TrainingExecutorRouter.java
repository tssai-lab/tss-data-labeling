package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TrainingExecutorRouter implements TrainingExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingExecutorRouter.class);

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final TrainingExperimentVersionRepository repository;
    private final KubernetesTrainingExecutor kubernetesTrainingExecutor;
    private final LocalTrainingExecutor localTrainingExecutor;

    public TrainingExecutorRouter(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            TrainingExperimentVersionRepository repository,
            KubernetesTrainingExecutor kubernetesTrainingExecutor,
            LocalTrainingExecutor localTrainingExecutor
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.repository = repository;
        this.kubernetesTrainingExecutor = kubernetesTrainingExecutor;
        this.localTrainingExecutor = localTrainingExecutor;
    }

    @Override
    public boolean isAvailable() {
        return localTrainingExecutor.isAvailable()
                || (properties.isEnabled() && environmentService.isKubernetesReady());
    }

    @Override
    public String getType() {
        return "router";
    }

    @Override
    public void start(String trainingId) {
        TrainingExecutor executor = resolveExecutor(trainingId);
        LOG.info("启动训练: trainingId={}, executor={}", trainingId, executor.getType());
        executor.start(trainingId);
    }

    @Override
    public void stop(String trainingId) {
        TrainingExecutor executor = resolveExecutorForStop(trainingId);
        LOG.info("停止训练: trainingId={}, executor={}", trainingId, executor.getType());
        executor.stop(trainingId);
    }

    private TrainingExecutor resolveExecutorForStop(String trainingId) {
        TrainingExperimentVersion task = repository.findById(trainingId).orElse(null);
        if (task != null && hasTrainingProfile(task)) {
            return kubernetesTrainingExecutor;
        }
        if (properties.isEnabled()) {
            return kubernetesTrainingExecutor;
        }
        return localTrainingExecutor;
    }

    private TrainingExecutor resolveExecutor(String trainingId) {
        TrainingExperimentVersion task = repository.findById(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("训练任务不存在: " + trainingId));
        if (!hasTrainingProfile(task)) {
            return localTrainingExecutor;
        }
        if (properties.isEnabled() && environmentService.isKubernetesReady()) {
            return kubernetesTrainingExecutor;
        }
        if (properties.isFallbackToLocal()) {
            LOG.warn("K8s 训练环境不可用，profile 任务无法回退本地训练: trainingId={}", trainingId);
        }
        throw new IllegalStateException("K8s 训练环境不可用，无法执行 profile 训练任务");
    }

    private static boolean hasTrainingProfile(TrainingExperimentVersion task) {
        return task.getTrainingProfile() != null && !task.getTrainingProfile().isBlank();
    }
}
