package com.tss.platform.repository;

import com.tss.platform.entity.DatasetSample;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DatasetSampleRepository extends JpaRepository<DatasetSample, String> {
    Page<DatasetSample> findByDatasetVersionIdAndDeletedFalse(
            String datasetVersionId,
            Pageable pageable
    );

    Page<DatasetSample> findByDatasetVersionId(
            String datasetVersionId,
            Pageable pageable
    );

    List<DatasetSample> findByDatasetVersionId(String datasetVersionId);

    Optional<DatasetSample> findByIdAndDeletedFalse(String id);

    Optional<DatasetSample> findByIdAndDatasetVersionId(String id, String datasetVersionId);

    Page<DatasetSample> findByDatasetVersionIdInAndExternalIdAndDeletedFalse(
            Collection<String> datasetVersionIds,
            String externalId,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DatasetSample s where s.id = :id")
    Optional<DatasetSample> findByIdForUpdate(@Param("id") String id);

    Slice<DatasetSample> findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
            String datasetVersionId,
            Pageable pageable
    );

    List<DatasetSample> findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
            String datasetVersionId
    );

    List<DatasetSample> findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(
            String datasetVersionId,
            Collection<String> externalIds
    );

    List<DatasetSample> findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(
            String datasetVersionId,
            Collection<Integer> sampleIndexes
    );

    long countByDatasetVersionIdAndDeletedFalse(String datasetVersionId);

    long countByDatasetVersionIdAndCreatedByPackageIdIsNull(String datasetVersionId);

    long countByDatasetVersionIdAndCreatedByPackageIdAndDeletedFalse(
            String datasetVersionId,
            String createdByPackageId
    );

    @Query("""
            select count(s.id)
            from DatasetSample s
            where s.createdByPackageId = :packageId
              and s.deleted = false
              and exists (
                  select v.id
                  from DatasetVersion v
                  where v.id = s.datasetVersionId
                    and v.deleted = false
              )
            """)
    long countActiveByCreatedByPackageId(@Param("packageId") String packageId);

    @Query("""
            select s.externalId
            from DatasetSample s
            where s.datasetVersionId = :datasetVersionId
              and s.deleted = false
            group by s.externalId
            having count(s.id) > 1
            """)
    List<String> findDuplicateExternalIdsByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    @Query("""
            select s.sampleIndex
            from DatasetSample s
            where s.datasetVersionId = :datasetVersionId
              and s.deleted = false
            group by s.sampleIndex
            having count(s.id) > 1
            """)
    List<Integer> findDuplicateSampleIndexesByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    @Query("""
            select distinct s.createdByPackageId
            from DatasetSample s
            where s.datasetVersionId = :datasetVersionId
              and s.createdByPackageId is not null
            """)
    List<String> findDistinctCreatedByPackageIdsByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    @Query("""
            select coalesce(max(s.sampleIndex), -1)
            from DatasetSample s
            where s.datasetVersionId = :datasetVersionId
              and s.deleted = false
            """)
    Integer findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
            @Param("datasetVersionId") String datasetVersionId
    );

    @Query("""
            select coalesce(max(s.sampleIndex), -1)
            from DatasetSample s
            where s.datasetVersionId = :datasetVersionId
            """)
    Integer findMaxSampleIndexByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    boolean existsByDatasetVersionIdAndExternalId(
            String datasetVersionId,
            String externalId
    );

    void deleteByDatasetVersionIdAndDeletedTrue(String datasetVersionId);
}
