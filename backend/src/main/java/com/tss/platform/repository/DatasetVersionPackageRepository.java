package com.tss.platform.repository;

import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.DatasetVersionPackageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DatasetVersionPackageRepository
        extends JpaRepository<DatasetVersionPackage, DatasetVersionPackageId> {

    Optional<DatasetVersionPackage> findByDatasetVersionIdAndPackageId(
            String datasetVersionId,
            String packageId
    );

    List<DatasetVersionPackage> findByDatasetVersionIdOrderByPackageOrderAsc(
            String datasetVersionId
    );

    List<DatasetVersionPackage> findByPackageId(String packageId);

    boolean existsByDatasetVersionIdAndPackageId(String datasetVersionId, String packageId);

    @Query("""
            select coalesce(max(vp.packageOrder), -1)
            from DatasetVersionPackage vp
            where vp.datasetVersionId = :datasetVersionId
            """)
    Integer findMaxPackageOrderByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId
    );

    void deleteByDatasetVersionIdAndPackageId(
            String datasetVersionId,
            String packageId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update DatasetVersionPackage vp
            set vp.packageOrder = vp.packageOrder - 1
            where vp.datasetVersionId = :datasetVersionId
              and vp.packageOrder > :removedOrder
            """)
    int compactPackageOrder(
            @Param("datasetVersionId") String datasetVersionId,
            @Param("removedOrder") Integer removedOrder
    );
}
