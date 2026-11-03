package com.tss.platform.repository;

import com.tss.platform.entity.DatasetSampleData;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DatasetSampleDataRepository extends JpaRepository<DatasetSampleData, String> {
    long countByDatasetVersionId(String datasetVersionId);

    long countByDatasetVersionIdAndDeletedFalse(String datasetVersionId);

    long countByDatasetVersionIdAndPackageIdIsNull(String datasetVersionId);

    @Query("""
            select count(d.id)
            from DatasetSampleData d
            where d.packageId = :packageId
              and exists (
                  select v.id
                  from DatasetVersion v
                  where v.id = d.datasetVersionId
                    and v.deleted = false
              )
            """)
    long countActiveByPackageId(@Param("packageId") String packageId);

    @Query("""
            select distinct d.packageId
            from DatasetSampleData d
            where d.datasetVersionId = :datasetVersionId
              and d.packageId is not null
            """)
    List<String> findDistinctPackageIdsByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    List<DatasetSampleData> findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
            String sampleId,
            String datasetVersionId
    );

    List<DatasetSampleData> findBySampleIdAndDatasetVersionIdAndDeletedFalseOrderBySeqAscIdAsc(
            String sampleId,
            String datasetVersionId
    );

    Optional<DatasetSampleData> findByIdAndDatasetVersionId(
            String id,
            String datasetVersionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from DatasetSampleData d
            where d.id = :id and d.datasetVersionId = :datasetVersionId
            """)
    Optional<DatasetSampleData> findByIdAndDatasetVersionIdForUpdate(
            @Param("id") String id,
            @Param("datasetVersionId") String datasetVersionId
    );

    List<DatasetSampleData> findByDatasetVersionId(String datasetVersionId);

    List<DatasetSampleData>
            findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                    String datasetVersionId,
                    Collection<String> sampleIds
            );

    List<DatasetSampleData> findBySampleIdInOrderBySampleIdAscSeqAscIdAsc(
            Collection<String> sampleIds
    );

    long countByDatasetVersionIdAndSampleIdAndDeletedFalse(
            String datasetVersionId,
            String sampleId
    );

    long countByDatasetVersionIdAndPackageIdAndDeletedFalse(
            String datasetVersionId,
            String packageId
    );

    long countByDatasetVersionIdAndPackageId(
            String datasetVersionId,
            String packageId
    );

    void deleteByDatasetVersionIdAndDeletedTrue(String datasetVersionId);
}
