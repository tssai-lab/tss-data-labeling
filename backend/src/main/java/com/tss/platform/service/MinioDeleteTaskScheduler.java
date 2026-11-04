package com.tss.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class MinioDeleteTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(MinioDeleteTaskScheduler.class);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(55);

    private final MinioDeleteTaskService taskService;
    private final SchedulerLockService lockService;
    private final TaskExecutor executor;

    public MinioDeleteTaskScheduler(
            MinioDeleteTaskService taskService,
            SchedulerLockService lockService,
            @Qualifier("minioDeleteTaskExecutor") TaskExecutor executor
    ) {
        this.taskService = taskService;
        this.lockService = lockService;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void processPendingTasks() {
        lockService.runWithLock("minio-delete-task", LOCK_AT_MOST_FOR, this::dispatchPendingTasks);
    }

    private void dispatchPendingTasks() {
        List<String> taskIds = taskService.findPendingTaskIds();
        if (taskIds.isEmpty()) {
            return;
        }
        log.info("MinIO delete scheduler picked {} pending task(s)", taskIds.size());
        for (String taskId : taskIds) {
            executor.execute(() -> taskService.processPendingTask(taskId));
        }
    }
}
