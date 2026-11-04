package com.tss.platform.repository;

import com.tss.platform.entity.TrainingPlanDefinitionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingPlanDefinitionRepository
        extends JpaRepository<TrainingPlanDefinitionEntity, Long> {

    Optional<TrainingPlanDefinitionEntity> findByPlanIdAndPlanVersion(
            String planId,
            String planVersion
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select plan from TrainingPlanDefinitionEntity plan
            where plan.planId = :planId and plan.planVersion = :planVersion
            """)
    Optional<TrainingPlanDefinitionEntity> findVersionForUpdate(
            @Param("planId") String planId,
            @Param("planVersion") String planVersion
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select plan from TrainingPlanDefinitionEntity plan
            where plan.planId = :planId and plan.status = 'ACTIVE'
            order by plan.id
            """)
    List<TrainingPlanDefinitionEntity> findActiveByPlanIdForUpdate(
            @Param("planId") String planId
    );

    List<TrainingPlanDefinitionEntity> findByStatusOrderByPlanIdAscPlanVersionAsc(String status);

    List<TrainingPlanDefinitionEntity> findAllByOrderByPlanIdAscPlanVersionAsc();

    List<TrainingPlanDefinitionEntity> findByPlanIdOrderByPlanVersionAsc(String planId);
}
