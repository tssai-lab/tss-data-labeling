package com.tss.platform.repository;

import com.tss.platform.entity.ModelVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, String> {
    List<ModelVersion> findByAssetId(String assetId);

    List<ModelVersion> findByAssetIdAndDeletedFalse(String assetId);

    List<ModelVersion> findByAssetIdIn(Collection<String> assetIds);

    List<ModelVersion> findByAssetIdInAndDeletedFalse(Collection<String> assetIds);

    List<ModelVersion> findByDeletedFalse();

    List<ModelVersion> findByOwnerUserId(Integer ownerUserId);

    List<ModelVersion> findByOwnerUserIdAndDeletedFalse(Integer ownerUserId);

    List<ModelVersion> findByAssetIdAndOwnerUserId(String assetId, Integer ownerUserId);

    List<ModelVersion> findByAssetIdAndOwnerUserIdAndDeletedFalse(String assetId, Integer ownerUserId);

    @Query("""
            select v from ModelVersion v
            join ModelAsset a on a.id = v.assetId
            where v.deleted = false
              and a.deleted = false
              and (:ownerUserId is null or a.isDemo = true or a.ownerUserId = :ownerUserId)
              and (:type is null or a.type = :type)
              and (
                    :keyword is null
                    or lower(a.name) like :keyword escape '!'
              )
            order by v.createdAt desc, v.id desc
            """)
    Page<ModelVersion> searchVisibleCatalog(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("type") String type,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            select v from ModelVersion v
            join ModelAsset a on a.id = v.assetId
            where v.deleted = false
              and a.deleted = false
              and v.status = 'READY'
              and v.artifactSpecId in :artifactSpecIds
              and (:ownerUserId is null or a.isDemo = true or a.ownerUserId = :ownerUserId)
              and (:type is null or a.type = :type)
              and (
                    :keyword is null
                    or lower(a.name) like :keyword escape '!'
              )
            order by v.createdAt desc, v.id desc
            """)
    Page<ModelVersion> searchVisibleTrainingCandidates(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("artifactSpecIds") Collection<String> artifactSpecIds,
            Pageable pageable
    );

    Optional<ModelVersion> findByIdAndDeletedFalse(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ModelVersion v where v.id = :id and v.deleted = false")
    Optional<ModelVersion> findByIdAndDeletedFalseForUpdate(@Param("id") String id);

    Optional<ModelVersion> findByAssetIdAndVersionAndDeletedFalse(String assetId, String version);

    Optional<ModelVersion> findFirstByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByCreatedAtDesc(
            String assetId
    );

    long countByAssetIdAndDeletedFalse(String assetId);

    boolean existsByAssetIdAndVersion(String assetId, String version);

    boolean existsByAssetIdAndVersionAndIdNot(String assetId, String version, String id);
}
