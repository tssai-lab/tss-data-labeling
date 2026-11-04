package com.tss.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrainingModelPublishScheduler {

    private final TrainingModelPublishService publishService;
    private final TaskExecutor executor;

    public TrainingModelPublishScheduler(
            TrainingModelPublishService publishService,
            @Qualifier("trainingModelPublishExecutor") TaskExecutor executor
    ) {
        this.publishService = publishService;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${training.model-publish.interval-ms:5000}")
    public void enqueuePendingPublishes() {
        publishService.recoverStalePublishes();
        for (String trainingId : publishService.pendingTrainingIds()) {
            executor.execute(() -> publishService.publishIfPending(trainingId));
        }
    }
}
