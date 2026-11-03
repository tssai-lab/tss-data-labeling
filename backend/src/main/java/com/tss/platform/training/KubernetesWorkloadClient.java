package com.tss.platform.training;

import java.time.Instant;
import java.util.Optional;

/**
 * 训练执行器使用的最小权限 Kubernetes 控制接口。
 *
 * <p>仅暴露训练需要的 Job 操作，不允许调用者传入任意 kubectl 命令或替换凭据。
 * Fabric8 与 kubectl 是互斥实现，不是自动故障回退链；本接口尚不承接推理执行器。</p>
 */
public interface KubernetesWorkloadClient {

    record TrainingJobStatus(
            int succeeded,
            int failed,
            int active,
            String podWaitingReason,
            String podWaitingMessage,
            Instant podCreatedAt
    ) {
    }

    void applyTrainingJob(String namespace, String jobName, String jobYaml);

    boolean trainingJobExists(String namespace, String jobName);

    /**
     * 同时读取 Job 计数与最新 Pod 的启动状态。
     *
     * @return 仅当目标 Job 不存在时返回空；API 故障必须抛错，不能伪装成任务不存在
     */
    Optional<TrainingJobStatus> getTrainingJobStatus(String namespace, String jobName);

    void deleteTrainingJob(String namespace, String jobName);
}
