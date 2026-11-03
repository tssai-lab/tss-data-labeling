package com.tss.platform.repository;

import com.tss.platform.entity.CodeWorkspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CodeWorkspaceRepository extends JpaRepository<CodeWorkspace, String> {

    Optional<CodeWorkspace> findByIdAndDeletedFalse(String id);

    @Query("select w.assetId from CodeWorkspace w where w.id = :id and w.deleted = false")
    Optional<String> findAssetIdByIdAndDeletedFalse(@Param("id") String id);

    @Query("select w.revision from CodeWorkspace w where w.id = :id and w.deleted = false")
    Optional<Long> findRevisionByIdAndDeletedFalse(@Param("id") String id);

    @Query("""
            select w from CodeWorkspace w
            where w.assetId = :assetId
              and w.status = 'OPEN'
              and w.deleted = false
            """)
    Optional<CodeWorkspace> findOpenByAssetId(@Param("assetId") String assetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select w from CodeWorkspace w
            where w.assetId = :assetId
              and w.status = 'OPEN'
              and w.deleted = false
            """)
    Optional<CodeWorkspace> findOpenByAssetIdForUpdate(@Param("assetId") String assetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from CodeWorkspace w where w.id = :id and w.deleted = false")
    Optional<CodeWorkspace> findByIdAndDeletedFalseForUpdate(@Param("id") String id);
}
