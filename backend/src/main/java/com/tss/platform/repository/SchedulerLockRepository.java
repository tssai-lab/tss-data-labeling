package com.tss.platform.repository;

import com.tss.platform.entity.SchedulerLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SchedulerLockRepository extends JpaRepository<SchedulerLock, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SchedulerLock l where l.name = :name")
    Optional<SchedulerLock> findByIdForUpdate(@Param("name") String name);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SchedulerLock l
            set l.lockedUntil = :releasedAt,
                l.updatedAt = :releasedAt
            where l.name = :name
              and l.ownerId = :ownerId
            """)
    int releaseIfOwner(
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("releasedAt") Instant releasedAt
    );
}
