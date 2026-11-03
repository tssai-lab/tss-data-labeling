package com.tss.platform.repository;

import com.tss.platform.entity.CodeVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface CodeVersionRepository extends JpaRepository<CodeVersion, String>,
        JpaSpecificationExecutor<CodeVersion> {

    Optional<CodeVersion> findByIdAndDeletedFalse(String id);

    Optional<CodeVersion> findByIdAndAssetIdAndDeletedFalse(String id, String assetId);

    @Query("select v.assetId from CodeVersion v where v.id = :id and v.deleted = false")
    Optional<String> findAssetIdByIdAndDeletedFalse(@Param("id") String id);

    boolean existsByAssetIdAndVersion(String assetId, String version);

    boolean existsByAssetIdAndVersionAndDeletedFalse(String assetId, String version);

    boolean existsByAssetIdAndDeletedFalse(String assetId);

    List<CodeVersion> findByDeletedFalseOrderByCreatedAtDesc();

    List<CodeVersion> findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(String assetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CodeVersion v where v.id = :id and v.deleted = false")
    Optional<CodeVersion> findByIdAndDeletedFalseForUpdate(@Param("id") String id);
}
