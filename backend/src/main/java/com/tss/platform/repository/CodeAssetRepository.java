package com.tss.platform.repository;

import com.tss.platform.entity.CodeAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CodeAssetRepository extends JpaRepository<CodeAsset, String>,
        JpaSpecificationExecutor<CodeAsset> {

    Optional<CodeAsset> findByIdAndDeletedFalse(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CodeAsset a where a.id = :id and a.deleted = false")
    Optional<CodeAsset> findByIdAndDeletedFalseForUpdate(@Param("id") String id);

    List<CodeAsset> findByOwnerUserIdAndDeletedFalseOrderByCreatedAtDesc(Integer ownerUserId);

    /** 演示训练代码：全局共享、只读 */
    List<CodeAsset> findByIsDemoTrueAndDeletedFalseOrderByCreatedAtDesc();

    @Query("""
            select (count(a) > 0)
            from CodeAsset a
            where a.deleted = false
              and a.ownerUserId = :ownerUserId
              and function('normalize_asset_name', a.name) = lower(:normalizedName)
              and (:excludedId is null or a.id <> :excludedId)
            """)
    boolean existsActiveNormalizedName(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("normalizedName") String normalizedName,
            @Param("excludedId") String excludedId
    );
}
