package com.tss.platform.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DatasetUploadRecoveryScheduler {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(55);

    private final DatasetUploadRecoveryService recoveryService;
    private final SchedulerLockService lockService;

    public DatasetUploadRecoveryScheduler(
            DatasetUploadRecoveryService recoveryService,
            SchedulerLockService lockService
    ) {
        this.recoveryService = recoveryService;
        this.lockService = lockService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverCompletingUploads();
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverCompletingUploads() {
        lockService.runWithLock(
                "dataset-upload-recovery",
                LOCK_AT_MOST_FOR,
                recoveryService::recoverStaleSessions
        );
    }
}
