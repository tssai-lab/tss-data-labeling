package com.tss.platform.service;

import com.tss.platform.entity.SchedulerLock;
import com.tss.platform.repository.SchedulerLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class SchedulerLockService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockService.class);

    private final SchedulerLockRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Supplier<String> ownerIdSupplier;

    @Autowired
    public SchedulerLockService(
            SchedulerLockRepository repo,
            PlatformTransactionManager transactionManager
    ) {
        this(repo, transactionManager, Clock.systemUTC(), SchedulerLockService::defaultOwnerId);
    }

    SchedulerLockService(
            SchedulerLockRepository repo,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Supplier<String> ownerIdSupplier
    ) {
        this.repo = repo;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.ownerIdSupplier = ownerIdSupplier;
    }

    public boolean runWithLock(String name, Duration lockAtMostFor, Runnable task) {
        String cleanName = requireText(name, "scheduler lock name cannot be blank");
        Duration duration = lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero()
                ? Duration.ofMinutes(1)
                : lockAtMostFor;
        String ownerId = ownerIdSupplier.get();
        boolean acquired = acquire(cleanName, duration, ownerId);
        if (!acquired) {
            log.debug("Skip scheduled task because lock is held: name={}", cleanName);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            release(cleanName, ownerId);
        }
    }

    private boolean acquire(String name, Duration lockAtMostFor, String ownerId) {
        try {
            Boolean acquired = transactionTemplate.execute(status -> {
                Instant now = clock.instant();
                Optional<SchedulerLock> existing = repo.findByIdForUpdate(name);
                if (existing.isPresent()) {
                    SchedulerLock lock = existing.get();
                    if (lock.getLockedUntil() != null && lock.getLockedUntil().isAfter(now)) {
                        return false;
                    }
                    lock.setOwnerId(ownerId);
                    lock.setLockedUntil(now.plus(lockAtMostFor));
                    lock.setUpdatedAt(now);
                    repo.save(lock);
                    return true;
                }
                SchedulerLock lock = new SchedulerLock();
                lock.setName(name);
                lock.setOwnerId(ownerId);
                lock.setLockedUntil(now.plus(lockAtMostFor));
                lock.setUpdatedAt(now);
                repo.save(lock);
                return true;
            });
            return Boolean.TRUE.equals(acquired);
        } catch (DataIntegrityViolationException exception) {
            log.debug("Lost scheduler lock insert race: name={}", name);
            return false;
        }
    }

    private void release(String name, String ownerId) {
        transactionTemplate.executeWithoutResult(status ->
                repo.releaseIfOwner(name, ownerId, clock.instant())
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String defaultOwnerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception exception) {
            return "unknown-" + UUID.randomUUID();
        }
    }
}
