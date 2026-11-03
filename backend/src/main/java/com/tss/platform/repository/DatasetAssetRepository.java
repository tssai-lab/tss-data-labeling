package com.tss.platform.repository;

import com.tss.platform.entity.DatasetAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DatasetAssetRepository extends JpaRepository<DatasetAsset, String> {
    Optional<DatasetAsset> findByIdAndDeletedFalse(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DatasetAsset a where a.id = :id and a.deleted = false")
    Optional<DatasetAsset> findByIdAndDeletedFalseForUpdate(@Param("id") String id);

    List<DatasetAsset> findByDeletedFalse();

    List<DatasetAsset> findByOwnerUserId(Integer ownerUserId);

    List<DatasetAsset> findByOwnerUserIdAndDeletedFalse(Integer ownerUserId);

    long countByCurrentVersionIdAndDeletedFalse(String currentVersionId);

    @Query("""
            select (count(a) > 0)
            from DatasetAsset a
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

    @Query(
            value = """
                    select a
                    from DatasetAsset a
                    where a.deleted = false
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    order by coalesce(a.updatedAt, a.createdAt) desc, a.id asc
                    """,
            countQuery = """
                    select count(a)
                    from DatasetAsset a
                    where a.deleted = false
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    """
    )
    Page<DatasetAsset> searchCatalogForAdmin(
            @Param("type") String type,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                    select a
                    from DatasetAsset a
                    where a.deleted = false
                      and (a.isDemo = true or a.ownerUserId = :ownerUserId)
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    order by coalesce(a.updatedAt, a.createdAt) desc, a.id asc
                    """,
            countQuery = """
                    select count(a)
                    from DatasetAsset a
                    where a.deleted = false
                      and (a.isDemo = true or a.ownerUserId = :ownerUserId)
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    """
    )
    Page<DatasetAsset> searchCatalogForOwner(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("type") String type,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                    select a
                    from DatasetAsset a
                    join DatasetVersion v on v.id = a.currentVersionId
                    where a.deleted = false
                      and v.deleted = false
                      and v.status = 'READY'
                      and v.artifactSpecId in :artifactSpecIds
                      and (:ownerUserId is null or a.isDemo = true or a.ownerUserId = :ownerUserId)
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    order by coalesce(a.updatedAt, a.createdAt) desc, a.id asc
                    """,
            countQuery = """
                    select count(a)
                    from DatasetAsset a
                    join DatasetVersion v on v.id = a.currentVersionId
                    where a.deleted = false
                      and v.deleted = false
                      and v.status = 'READY'
                      and v.artifactSpecId in :artifactSpecIds
                      and (:ownerUserId is null or a.isDemo = true or a.ownerUserId = :ownerUserId)
                      and (:type is null or a.type = :type)
                      and (
                            :keyword is null
                            or lower(a.name) like concat('%', cast(:keyword as string), '%') escape '!'
                      )
                    """
    )
    Page<DatasetAsset> searchTrainingCandidates(
            @Param("ownerUserId") Integer ownerUserId,
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("artifactSpecIds") List<String> artifactSpecIds,
            Pageable pageable
    );
}
