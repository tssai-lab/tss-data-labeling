package com.tss.platform.repository;

import com.tss.platform.entity.ModelAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModelAssetRepository extends JpaRepository<ModelAsset, String> {
    Optional<ModelAsset> findByName(String name);

    Optional<ModelAsset> findByIdAndDeletedFalse(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ModelAsset a where a.id = :id and a.deleted = false")
    Optional<ModelAsset> findByIdAndDeletedFalseForUpdate(@Param("id") String id);

    Optional<ModelAsset> findByNameAndDeletedFalse(String name);

    List<ModelAsset> findByDeletedFalse();

    List<ModelAsset> findByOwnerUserId(Integer ownerUserId);

    List<ModelAsset> findByOwnerUserIdAndDeletedFalse(Integer ownerUserId);

    @Query("""
            select (count(a) > 0)
            from ModelAsset a
            where a.deleted = false
              and a.ownerUserId = :ownerUserId
              and lower(trim(a.name)) = lower(:normalizedName)
              and (:excludedId is null or a.id <> :excludedId)
            """)
    boolean existsActiveNormalizedName(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("normalizedName") String normalizedName,
            @Param("excludedId") String excludedId
    );
}

