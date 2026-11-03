package com.tss.platform.training;

public interface TrainingExecutor {

    /** 是否当前可用（例如 K8s 环境已就绪） */
    boolean isAvailable();

    /** 执行器类型标识：kubernetes / local */
    String getType();

    void start(String trainingId);

    void stop(String trainingId);
}
