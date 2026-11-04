package com.tss.platform.repository;

import com.tss.platform.entity.ServerMetricHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ServerMetricHistoryRepository extends JpaRepository<ServerMetricHistory, Long> {

    List<ServerMetricHistory> findByServerIpAndCollectedAtBetweenOrderByCollectedAtAsc(
            String serverIp, Instant from, Instant to, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ServerMetricHistory h WHERE h.collectedAt < :before")
    int deleteByCollectedAtBefore(@Param("before") Instant before);

    ServerMetricHistory findFirstByServerIpOrderByCollectedAtDesc(String serverIp);
}
