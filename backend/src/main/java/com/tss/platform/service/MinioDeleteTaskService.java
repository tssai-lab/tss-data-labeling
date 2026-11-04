package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.repository.MinioDeleteTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MinioDeleteTaskService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SOURCE_MODEL_VERSION = "MODEL_VERSION";
    public static final String SOURCE_DATASET_VERSION = "DATASET_VERSION";
    public static final String SOURCE_MODEL_ASSET = "MODEL_ASSET";
    public static final String SOURCE_DATASET_ASSET = "DATASET_ASSET";
    public static final String SOURCE_DATASET_PACKAGE = "DATASET_PACKAGE";
    public static final String SOURCE_FILE_OBJECT = "FILE_OBJECT";
    public static final String SOURCE_MODEL_UPLOAD_CHUNK = "MODEL_UPLOAD_CHUNK";
    public static final String SOURCE_DATASET_UPLOAD_CHUNK = "DATASET_UPLOAD_CHUNK";
    public static final String SOURCE_MODEL_UPLOAD_ROLLBACK = "MODEL_UPLOAD_ROLLBACK";
    public static final String SOURCE_DATASET_UPLOAD_ROLLBACK = "DATASET_UPLOAD_ROLLBACK";
    public static final String SOURCE_CODE_ARTIFACT_ROLLBACK = "CODE_ARTIFACT_ROLLBACK";
    public static final String SOURCE_CODE_ARTIFACT_UPGRADE = "CODE_ARTIFACT_UPGRADE";
    public static final String SOURCE_TRAINING_FAILURE_DIAGNOSTIC = "TRAINING_FAILURE_DIAGNOSTIC";

    private static final Logger log = LoggerFactory.getLogger(MinioDeleteTaskService.class);
    private static final int DEFAULT_MAX_RETRY_COUNT = 5;
    // Initial 5 attempts plus 2 reset cycles gives at most 15 delete attempts.
    private static final int FAILED_RESET_LIMIT = 2;
    private static final Duration PROCESSING_STALE_AFTER = Duration.ofMinutes(30);
    private static final Set<String> ACTIVE_STATUSES = Set.of(STATUS_PENDING, STATUS_PROCESSING);

    private final MinioDeleteTaskRepository repo;
    private final MinioService minioService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final String bucket;

    public MinioDeleteTaskService(
            MinioDeleteTaskRepository repo,
            MinioService minioService,
            MinioConfig minioConfig,
            PlatformTransactionManager transactionManager
    ) {
        this.repo = repo;
        this.minioService = minioService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bucket = minioConfig.getBucket();
    }

    @Transactional
    public MinioDeleteTask enqueueDefaultBucketDelete(
            String objectName,
            String sourceType,
            String sourceId,
            Integer ownerUserId
    ) {
        return enqueueDelete(bucket, objectName, sourceType, sourceId, ownerUserId);
    }

    public MinioDeleteTask enqueueDefaultBucketDeleteImmediately(
            String objectName,
            String sourceType,
            String sourceId,
            Integer ownerUserId
    ) {
        return requiresNewTransactionTemplate.execute(status ->
                enqueueDeleteInternal(bucket, objectName, sourceType, sourceId, ownerUserId)
        );
    }

    @Transactional
    public MinioDeleteTask enqueueDelete(
            String targetBucket,
            String objectName,
            String sourceType,
            String sourceId,
            Integer ownerUserId
    ) {
        return enqueueDeleteInternal(targetBucket, objectName, sourceType, sourceId, ownerUserId);
    }

    private MinioDeleteTask enqueueDeleteInternal(
            String targetBucket,
            String objectName,
            String sourceType,
            String sourceId,
            Integer ownerUserId
    ) {
        String cleanBucket = requireText(targetBucket, "bucket 不能为空");
        String cleanObjectName = requireText(objectName, "objectName 不能为空");
        String cleanSourceType = requireText(sourceType, "sourceType 不能为空");

        MinioDeleteTask existing = repo.findFirstByBucketAndObjectNameAndStatusIn(
                cleanBucket,
                cleanObjectName,
                ACTIVE_STATUSES
        ).orElse(null);
        if (existing != null) {
            log.info(
                    "MinIO delete task already active: taskId={}, bucket={}, objectName={}, sourceType={}, sourceId={}",
                    existing.getId(),
                    cleanBucket,
                    cleanObjectName,
                    cleanSourceType,
                    sourceId
            );
            return existing;
        }

        Instant now = Instant.now();
        MinioDeleteTask task = new MinioDeleteTask();
        task.setId("minio-del-" + UUID.randomUUID().toString().replace("-", ""));
        task.setBucket(cleanBucket);
        task.setObjectName(cleanObjectName);
        task.setSourceType(cleanSourceType);
        task.setSourceId(blankToNull(sourceId));
        task.setOwnerUserId(ownerUserId);
        task.setStatus(STATUS_PENDING);
        task.setRetryCount(0);
        task.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        MinioDeleteTask saved = repo.save(task);
        log.info(
                "MinIO delete task queued: taskId={}, bucket={}, objectName={}, sourceType={}, sourceId={}, ownerUserId={}",
                saved.getId(),
                saved.getBucket(),
                saved.getObjectName(),
                saved.getSourceType(),
                saved.getSourceId(),
                saved.getOwnerUserId()
        );
        return saved;
    }

    public List<String> findPendingTaskIds() {
        resetStaleProcessingTasks();
        resetFailedTasksForRetry();
        return repo.findTop50ByStatusOrderByCreatedAtAsc(STATUS_PENDING)
                .stream()
                .map(MinioDeleteTask::getId)
                .toList();
    }

    public int resetStaleProcessingTasks() {
        Instant now = Instant.now();
        Integer resetCount = transactionTemplate.execute(status -> repo.resetStaleProcessing(
                STATUS_PROCESSING,
                STATUS_PENDING,
                now.minus(PROCESSING_STALE_AFTER),
                now
        ));
        int value = resetCount == null ? 0 : resetCount;
        if (value > 0) {
            log.warn("MinIO delete stale PROCESSING tasks reset to PENDING: count={}", value);
        }
        return value;
    }

    public int resetFailedTasksForRetry() {
        Instant now = Instant.now();
        Integer resetCount = transactionTemplate.execute(status -> repo.resetFailedForRetry(
                STATUS_FAILED,
                STATUS_PENDING,
                FAILED_RESET_LIMIT,
                now
        ));
        int value = resetCount == null ? 0 : resetCount;
        if (value > 0) {
            log.warn("MinIO delete FAILED tasks reset to PENDING for retry: count={}", value);
        }
        return value;
    }

    public void processPendingTask(String taskId) {
        boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int updated = repo.claimPending(taskId, STATUS_PENDING, STATUS_PROCESSING, Instant.now());
            return updated == 1;
        }));
        if (!claimed) {
            return;
        }

        MinioDeleteTask task = repo.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("MinIO delete task disappeared after claim: taskId={}", taskId);
            return;
        }

        try {
            log.info(
                    "MinIO delete task processing: taskId={}, bucket={}, objectName={}",
                    task.getId(),
                    task.getBucket(),
                    task.getObjectName()
            );
            minioService.deleteObject(task.getBucket(), task.getObjectName());
            markSuccess(task.getId());
        } catch (Exception e) {
            if (isObjectMissing(e)) {
                log.info(
                        "MinIO delete task target already missing, marking success: taskId={}, bucket={}, objectName={}",
                        task.getId(),
                        task.getBucket(),
                        task.getObjectName()
                );
                markSuccess(task.getId());
            } else {
                markFailure(task.getId(), e);
            }
        }
    }

    private void markSuccess(String taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            MinioDeleteTask task = repo.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            Instant now = Instant.now();
            task.setStatus(STATUS_SUCCESS);
            task.setErrorMessage(null);
            task.setUpdatedAt(now);
            task.setCompletedAt(now);
            repo.save(task);
            log.info(
                    "MinIO delete task success: taskId={}, bucket={}, objectName={}",
                    task.getId(),
                    task.getBucket(),
                    task.getObjectName()
            );
        });
    }

    private void markFailure(String taskId, Exception e) {
        transactionTemplate.executeWithoutResult(status -> {
            MinioDeleteTask task = repo.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            Instant now = Instant.now();
            int retryCount = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
            task.setRetryCount(retryCount);
            task.setLastRetryAt(now);
            task.setUpdatedAt(now);
            task.setErrorMessage(errorMessage(e));
            if (retryCount >= task.getMaxRetryCount()) {
                task.setStatus(STATUS_FAILED);
                log.error(
                        "MinIO delete task failed permanently: taskId={}, retryCount={}, bucket={}, objectName={}, error={}",
                        task.getId(),
                        retryCount,
                        task.getBucket(),
                        task.getObjectName(),
                        task.getErrorMessage()
                );
            } else {
                task.setStatus(STATUS_PENDING);
                log.warn(
                        "MinIO delete task failed, will retry: taskId={}, retryCount={}, bucket={}, objectName={}, error={}",
                        task.getId(),
                        retryCount,
                        task.getBucket(),
                        task.getObjectName(),
                        task.getErrorMessage()
                );
            }
            repo.save(task);
        });
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 4000 ? message.substring(0, 4000) : message;
    }

    private boolean isObjectMissing(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("object does not exist")
                        || lower.contains("object not found")
                        || lower.contains("no such key")
                        || lower.contains("notfound")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
