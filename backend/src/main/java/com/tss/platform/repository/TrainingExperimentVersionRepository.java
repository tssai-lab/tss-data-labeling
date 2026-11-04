package com.tss.platform.repository;

import com.tss.platform.entity.TrainingExperimentVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrainingExperimentVersionRepository extends JpaRepository<TrainingExperimentVersion, String> {
    /** 停止只更新仍活跃的记录，同时丢弃请求线程缓存中的旧实体。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingExperimentVersion v set v.status = 'stopped',
                v.progress = coalesce(v.progress, 0), v.finishedAt = :now, v.updatedAt = :now,
                v.lockRevision = v.lockRevision + 1
            where v.id = :id and v.status in ('queued', 'scheduled', 'running')
            """)
    int stopIfActive(@Param("id") String id, @Param("now") Instant now);

    /** 先选每个实验最新版本，再筛选分页，避免读出整份训练历史。 */
    @Query("""
            select v from TrainingExperimentVersion v
             where (:owner is null or v.ownerUserId = :owner)
               and v.versionNo = (select max(n.versionNo) from TrainingExperimentVersion n
                    where n.experimentId = v.experimentId and (:owner is null or n.ownerUserId = :owner))
               and (:status is null or v.status = :status)
               and (:name is null or lower(v.name) like :name escape '!')
               and (:experiment is null or lower(v.experimentId) like :experiment escape '!')
             order by v.createdAt desc nulls last, v.id desc
            """)
    org.springframework.data.domain.Page<TrainingExperimentVersion> searchLatestExperiments(
            @Param("owner") Integer owner,
            @Param("status") String status,
            @Param("name") String name,
            @Param("experiment") String experiment,
            org.springframework.data.domain.Pageable pageable);
    List<TrainingExperimentVersion> findByExperimentIdOrderByVersionNoAsc(String experimentId);

    Optional<TrainingExperimentVersion> findByExperimentIdAndVersionNo(String experimentId, Integer versionNo);

    Optional<TrainingExperimentVersion> findTopByExperimentIdOrderByVersionNoDesc(String experimentId);

    List<TrainingExperimentVersion> findAllByOrderByCreatedAtDesc();

    List<TrainingExperimentVersion> findAllByOwnerUserIdOrderByCreatedAtDesc(Integer ownerUserId);

    List<TrainingExperimentVersion> findTop20ByModelPublishStatusOrderByUpdatedAtAsc(String modelPublishStatus);

    void deleteByExperimentId(String experimentId);

    long countByModelVersionId(String modelVersionId);

    long countByModelVersionIdIn(Collection<String> modelVersionIds);

    long countByProducedModelVersionId(String modelVersionId);

    long countByProducedModelVersionIdIn(Collection<String> modelVersionIds);

    long countByDatasetVersionId(String datasetVersionId);

    long countByDatasetVersionIdIn(Collection<String> datasetVersionIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingExperimentVersion v
               set v.modelPublishStatus = 'PUBLISHING',
                   v.modelPublishError = null,
                   v.lockRevision = v.lockRevision + 1,
                   v.updatedAt = :now
             where v.id = :id
               and v.status = 'success'
               and v.producedModelVersionId is null
               and v.modelPublishStatus in :claimableStatuses
            """)
    int claimModelPublish(
            @Param("id") String id,
            @Param("claimableStatuses") Collection<String> claimableStatuses,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingExperimentVersion v
               set v.modelPublishStatus = 'PENDING',
                   v.modelPublishError = :message,
                   v.lockRevision = v.lockRevision + 1,
                   v.updatedAt = :now
             where v.modelPublishStatus = 'PUBLISHING'
               and v.updatedAt < :staleBefore
               and v.producedModelVersionId is null
            """)
    int resetStaleModelPublishes(
            @Param("staleBefore") Instant staleBefore,
            @Param("message") String message,
            @Param("now") Instant now
    );

    // resource-monitor queue queries
    List<TrainingExperimentVersion> findByServerIpAndStatus(String serverIp, String status);

    List<TrainingExperimentVersion> findByServerIpAndStatusIn(String serverIp, List<String> statuses);

    List<TrainingExperimentVersion> findByServerIpNotNullAndStatusIn(List<String> statuses);

    List<TrainingExperimentVersion> findByStatus(String status);

    List<TrainingExperimentVersion> findTop100ByStatusAndLogPathIsNullAndFinishedAtAfterOrderByFinishedAtAsc(
            String status,
            Instant finishedAfter
    );

    @Query("""
            select v from TrainingExperimentVersion v
             where v.status in ('failed', 'stopped')
               and v.finishedAt < :finishedBefore
               and v.logPath like :diagnosticPathPattern
             order by v.finishedAt asc
            """)
    List<TrainingExperimentVersion> findExpiredFailureDiagnostics(
            @Param("finishedBefore") Instant finishedBefore,
            @Param("diagnosticPathPattern") String diagnosticPathPattern,
            org.springframework.data.domain.Pageable pageable
    );

    List<TrainingExperimentVersion> findByStatusAndServerIpIsNullOrderByPriorityAscCreatedAtAsc(String status);

    /**
     * 全局排队查询：所有未分配节点（serverIp 为 null）且处于 queued/pending 的训练任务。
     * 排序与 findAllPendingWithLock 一致（人工序号在前，其余按优先级+提交时间），但不加锁，供只读查询使用。
     */
    @Query("""
            select v from TrainingExperimentVersion v
             where v.serverIp is null
               and v.status in ('queued', 'pending')
             order by
               case when v.queueSortIndex is not null and v.queueSortIndex > 0 then 0 else 1 end asc,
               coalesce(v.queueSortIndex, 0) asc,
               case v.priority when '高' then 3 when '中' then 2 else 1 end desc,
               v.createdAt asc
            """)
    List<TrainingExperimentVersion> findUnassignedQueuedOrdered();

    /**
     * 原子绑定任务到节点：只有尚未分配节点（server_ip IS NULL）且仍处于 pending/queued 的任务才会被更新。
     * 返回更新行数：1=本次调用绑定成功；0=已被其他线程抢先绑定/任务已不在可绑定状态。
     * 用于防止 afterCommit 路径与调度循环并发绑定同一任务导致 serverIp 丢失。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingExperimentVersion v
               set v.serverIp = :serverIp,
                   v.status = 'scheduled',
                   v.lockRevision = v.lockRevision + 1,
                   v.updatedAt = :now
             where v.id = :id
               and v.serverIp is null
               and v.status in ('pending', 'queued')
            """)
    int atomicBindNode(
            @Param("id") String id,
            @Param("serverIp") String serverIp,
            @Param("now") Instant now
    );

    /**
     * 悲观锁查询所有未分配节点（pending/queued）的任务，用于 @Scheduled 调度循环，
     * 串行化节点分配与绑定，避免并发分配重复占用节点资源。需要事务上下文。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from TrainingExperimentVersion v
             where v.serverIp is null
               and v.status in ('pending', 'queued')
             order by
               case when v.queueSortIndex is not null and v.queueSortIndex > 0 then 0 else 1 end asc,
               coalesce(v.queueSortIndex, 0) asc,
               case v.priority when '高' then 3 when '中' then 2 else 1 end desc,
               v.createdAt asc
            """)
    List<TrainingExperimentVersion> findAllPendingWithLock();
}
