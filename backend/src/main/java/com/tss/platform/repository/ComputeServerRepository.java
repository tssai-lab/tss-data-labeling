package com.tss.platform.repository;

import com.tss.platform.entity.ComputeServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComputeServerRepository extends JpaRepository<ComputeServer, String> {

    Optional<ComputeServer> findByServerIpAndDeletedFalse(String serverIp);

    List<ComputeServer> findByDeletedFalse();

    boolean existsByServerIpAndDeletedFalse(String serverIp);
}
