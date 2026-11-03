package com.tss.platform.repository;

import com.tss.platform.entity.ServerMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServerMetricSnapshotRepository extends JpaRepository<ServerMetricSnapshot, String> {

    Optional<ServerMetricSnapshot> findByServerIp(String serverIp);
}
