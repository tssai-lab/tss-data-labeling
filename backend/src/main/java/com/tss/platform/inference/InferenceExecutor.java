package com.tss.platform.inference;

public interface InferenceExecutor {
    boolean isAvailable();

    String getType();

    void start(String taskId);

    void stop(String taskId);
}
