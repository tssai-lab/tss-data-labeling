package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioDeleteTaskSchedulerTest {

    @Test
    void processPendingTasksUsesSchedulerLockAndDispatchesDeletesToExecutor() {
        MinioDeleteTaskService taskService = mock(MinioDeleteTaskService.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        when(taskService.findPendingTaskIds()).thenReturn(List.of("minio-del-1"));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(lockService).runWithLock(
                eq("minio-delete-task"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        MinioDeleteTaskScheduler scheduler =
                new MinioDeleteTaskScheduler(taskService, lockService, executor);

        scheduler.processPendingTasks();

        verify(lockService).runWithLock(
                eq("minio-delete-task"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        verify(executor).execute(any(Runnable.class));
        verify(taskService, never()).processPendingTask("minio-del-1");
    }
}
