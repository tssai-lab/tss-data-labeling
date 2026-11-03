package com.tss.platform.service;

import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportJobRecoverySchedulerTest {

    @Test
    void resetsStaleJobsAndLaunchesPendingJobs() {
        ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        ImportJobLauncher launcher = mock(ImportJobLauncher.class);
        ImportJobService jobService = mock(ImportJobService.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(lockService).runWithLock(
                eq("import-job-recovery"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        ImportJob pending = new ImportJob();
        pending.setId("ijob-pending");
        when(jobRepo.resetStaleRunning(
                eq("RUNNING"),
                eq("PENDING"),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(1);
        when(jobRepo.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pending));
        ImportJobRecoveryScheduler scheduler =
                new ImportJobRecoveryScheduler(jobRepo, launcher, jobService, lockService);

        scheduler.recoverJobs();

        verify(lockService).runWithLock(
                eq("import-job-recovery"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        verify(jobRepo).resetStaleRunning(
                eq("RUNNING"),
                eq("PENDING"),
                any(Instant.class),
                any(Instant.class)
        );
        verify(launcher).launch("ijob-pending");
    }

    @Test
    void heartbeatTickUpdatesOnlyLocallyActiveLeases() {
        ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        ImportJobLauncher launcher = mock(ImportJobLauncher.class);
        ImportJobService jobService = mock(ImportJobService.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        ImportJobRecoveryScheduler scheduler =
                new ImportJobRecoveryScheduler(jobRepo, launcher, jobService, lockService);

        scheduler.heartbeat();

        verify(jobService).heartbeatActiveJobs();
    }

    @Test
    void recoveryOnlyResetsRunningAndLaunchesPendingJobs() {
        ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        ImportJobLauncher launcher = mock(ImportJobLauncher.class);
        ImportJobService jobService = mock(ImportJobService.class);
        SchedulerLockService lockService = mock(SchedulerLockService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(lockService).runWithLock(
                eq("import-job-recovery"),
                eq(Duration.ofSeconds(55)),
                any(Runnable.class)
        );
        when(jobRepo.findTop100ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());
        ImportJobRecoveryScheduler scheduler =
                new ImportJobRecoveryScheduler(jobRepo, launcher, jobService, lockService);

        scheduler.recoverJobs();

        verify(jobRepo).resetStaleRunning(
                eq("RUNNING"),
                eq("PENDING"),
                any(Instant.class),
                any(Instant.class)
        );
        verify(jobRepo).findTop100ByStatusOrderByCreatedAtAsc("PENDING");
        verify(jobRepo, never()).findTop100ByStatusOrderByCreatedAtAsc("PARTIAL");
        verify(jobRepo, never()).findTop100ByStatusOrderByCreatedAtAsc("FAILED");
        verify(jobRepo, never()).findTop100ByStatusOrderByCreatedAtAsc("SUCCESS");
        verify(launcher, never()).launch("ijob-partial");
    }
}
