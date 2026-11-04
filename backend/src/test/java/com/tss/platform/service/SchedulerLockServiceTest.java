package com.tss.platform.service;

import com.tss.platform.entity.SchedulerLock;
import com.tss.platform.repository.SchedulerLockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLockServiceTest {

    @Test
    void runsTaskWhenLockIsAbsent() {
        SchedulerLockRepository repo = mock(SchedulerLockRepository.class);
        when(repo.findByIdForUpdate("import-job-recovery")).thenReturn(Optional.empty());
        SchedulerLockService service = new SchedulerLockService(
                repo,
                new NoOpTransactionManager(),
                Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC),
                () -> "owner-1"
        );
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = service.runWithLock(
                "import-job-recovery",
                Duration.ofMinutes(5),
                () -> ran.set(true)
        );

        assertTrue(acquired);
        assertTrue(ran.get());
        verify(repo).save(any(SchedulerLock.class));
    }

    @Test
    void releasesLockWithAcquiredOwnerWhenTaskCompletes() {
        SchedulerLockRepository repo = mock(SchedulerLockRepository.class);
        when(repo.findByIdForUpdate("import-job-recovery")).thenReturn(Optional.empty());
        AtomicInteger ownerSequence = new AtomicInteger();
        SchedulerLockService service = new SchedulerLockService(
                repo,
                new NoOpTransactionManager(),
                Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC),
                () -> "owner-" + ownerSequence.incrementAndGet()
        );

        service.runWithLock(
                "import-job-recovery",
                Duration.ofMinutes(5),
                () -> {
                }
        );

        verify(repo).releaseIfOwner(
                eq("import-job-recovery"),
                eq("owner-1"),
                any(Instant.class)
        );
    }

    @Test
    void releasesLockWithAcquiredOwnerWhenTaskThrows() {
        SchedulerLockRepository repo = mock(SchedulerLockRepository.class);
        when(repo.findByIdForUpdate("import-job-recovery")).thenReturn(Optional.empty());
        SchedulerLockService service = new SchedulerLockService(
                repo,
                new NoOpTransactionManager(),
                Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC),
                () -> "owner-1"
        );

        assertThrows(IllegalStateException.class, () -> service.runWithLock(
                "import-job-recovery",
                Duration.ofMinutes(5),
                () -> {
                    throw new IllegalStateException("boom");
                }
        ));

        verify(repo).releaseIfOwner(
                eq("import-job-recovery"),
                eq("owner-1"),
                any(Instant.class)
        );
    }

    @Test
    void skipsTaskWhenLockIsStillActive() {
        SchedulerLockRepository repo = mock(SchedulerLockRepository.class);
        SchedulerLock active = new SchedulerLock();
        active.setName("import-job-recovery");
        active.setOwnerId("other-node");
        active.setLockedUntil(Instant.parse("2026-07-02T00:04:00Z"));
        when(repo.findByIdForUpdate("import-job-recovery")).thenReturn(Optional.of(active));
        SchedulerLockService service = new SchedulerLockService(
                repo,
                new NoOpTransactionManager(),
                Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC),
                () -> "owner-1"
        );
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = service.runWithLock(
                "import-job-recovery",
                Duration.ofMinutes(5),
                () -> ran.set(true)
        );

        assertFalse(acquired);
        assertFalse(ran.get());
        verify(repo, never()).save(any(SchedulerLock.class));
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
