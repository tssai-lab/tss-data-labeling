package com.tss.platform.service;

import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ImportJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRecoveryScheduler.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(55);

    private final ImportJobRepository jobRepo;
    private final ImportJobLauncher launcher;
    private final ImportJobService jobService;
    private final SchedulerLockService lockService;

    public ImportJobRecoveryScheduler(
            ImportJobRepository jobRepo,
            ImportJobLauncher launcher,
            ImportJobService jobService,
            SchedulerLockService lockService
    ) {
        this.jobRepo = jobRepo;
        this.launcher = launcher;
        this.jobService = jobService;
        this.lockService = lockService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverJobs();
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverJobs() {
        lockService.runWithLock("import-job-recovery", LOCK_AT_MOST_FOR, this::recoverJobsInternal);
    }

    private void recoverJobsInternal() {
        Instant now = Instant.now();
        jobRepo.resetStaleRunning("RUNNING", "PENDING", now.minus(STALE_AFTER), now);
        for (ImportJob job : jobRepo.findTop100ByStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                launcher.launch(job.getId());
            } catch (RuntimeException exception) {
                log.warn("Unable to enqueue import job {}: {}", job.getId(), exception.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        jobService.heartbeatActiveJobs();
    }
}
