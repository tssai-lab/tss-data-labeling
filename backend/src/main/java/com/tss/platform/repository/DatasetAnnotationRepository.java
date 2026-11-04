package com.tss.platform.repository;

import com.tss.platform.entity.DatasetAnnotation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DatasetAnnotationRepository extends JpaRepository<DatasetAnnotation, String> {
    long countByDatasetVersionId(String datasetVersionId);

    long countByDatasetVersionIdAndDeletedFalse(String datasetVersionId);

    long countByDatasetVersionIdAndPackageIdIsNull(String datasetVersionId);

    @Query("""
            select count(a.id)
            from DatasetAnnotation a
            where a.packageId = :packageId
              and exists (
                  select v.id
                  from DatasetVersion v
                  where v.id = a.datasetVersionId
                    and v.deleted = false
              )
            """)
    long countActiveByPackageId(@Param("packageId") String packageId);

    @Query("""
            select distinct a.packageId
            from DatasetAnnotation a
            where a.datasetVersionId = :datasetVersionId
              and a.packageId is not null
            """)
    List<String> findDistinctPackageIdsByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    List<DatasetAnnotation> findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
            String sampleId,
            String datasetVersionId
    );

    List<DatasetAnnotation>
            findBySampleIdAndDatasetVersionIdAndDeletedFalseOrderByCreatedAtAscIdAsc(
                    String sampleId,
                    String datasetVersionId
            );

    Optional<DatasetAnnotation> findByIdAndDatasetVersionId(
            String id,
            String datasetVersionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from DatasetAnnotation a
            where a.id = :id and a.datasetVersionId = :datasetVersionId
            """)
    Optional<DatasetAnnotation> findByIdAndDatasetVersionIdForUpdate(
            @Param("id") String id,
            @Param("datasetVersionId") String datasetVersionId
    );

    List<DatasetAnnotation> findByDatasetVersionId(String datasetVersionId);

    List<DatasetAnnotation>
            findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                    String datasetVersionId,
                    Collection<String> sampleIds
            );

    List<DatasetAnnotation> findBySampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
            Collection<String> sampleIds
    );

    long countByDatasetVersionIdAndSampleDataIdAndDeletedFalse(
            String datasetVersionId,
            String sampleDataId
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
