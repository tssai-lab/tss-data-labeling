package com.tss.platform.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DatasetLifecycleMaintenanceScheduler {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(55);

    private final DatasetLifecycleMaintenanceService maintenanceService;
    private final SchedulerLockService lockService;

    public DatasetLifecycleMaintenanceScheduler(
            DatasetLifecycleMaintenanceService maintenanceService,
            SchedulerLockService lockService
    ) {
        this.maintenanceService = maintenanceService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void maintainDatasetLifecycle() {
        lockService.runWithLock(
                "dataset-lifecycle-maintenance",
                LOCK_AT_MOST_FOR,
                this::maintainDatasetLifecycleInternal
        );
    }

    private void maintainDatasetLifecycleInternal() {
        maintenanceService.cleanupFailedDrafts();
        maintenanceService.purgeSoftDeletedVersions();
    }
}
