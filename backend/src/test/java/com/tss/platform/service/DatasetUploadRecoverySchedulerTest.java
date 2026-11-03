package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DatasetUploadRecoverySchedulerTest {

    @Test
    void startupRecoveryUsesDistributedLock() {
        DatasetUploadRecoveryService recoveryService = mock(DatasetUploadRecoveryService.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        DatasetUploadRecoveryScheduler scheduler =
                new DatasetUploadRecoveryScheduler(recoveryService, lockService);

        scheduler.recoverOnStartup();

        verify(lockService).runWithLock(
                eq("dataset-upload-recovery"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        verify(recoveryService, never()).recoverStaleSessions();
    }
}
