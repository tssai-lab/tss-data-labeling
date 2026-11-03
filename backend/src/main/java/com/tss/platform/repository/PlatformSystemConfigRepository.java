package com.tss.platform.repository;

import com.tss.platform.entity.PlatformSystemConfig;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlatformSystemConfigRepository
        extends JpaRepository<PlatformSystemConfig, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select config from PlatformSystemConfig config where config.id = :id")
    Optional<PlatformSystemConfig> findByIdForUpdate(@Param("id") String id);
}
