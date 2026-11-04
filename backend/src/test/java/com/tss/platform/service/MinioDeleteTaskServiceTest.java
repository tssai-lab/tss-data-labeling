package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.repository.MinioDeleteTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioDeleteTaskServiceTest {

    @Test
    void exposesDedicatedCodeArtifactUpgradeSourceType() {
        assertEquals("CODE_ARTIFACT_UPGRADE",
                MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_UPGRADE);
    }

    @Test
    void resetsStaleProcessingTasksBeforePollingPendingIds() {
        Fixture fixture = new Fixture();
        MinioDeleteTask pending = new MinioDeleteTask();
        pending.setId("minio-del-1");
        when(fixture.repo.resetStaleProcessing(
                eq(MinioDeleteTaskService.STATUS_PROCESSING),
                eq(MinioDeleteTaskService.STATUS_PENDING),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(1);
        when(fixture.repo.resetFailedForRetry(
                eq(MinioDeleteTaskService.STATUS_FAILED),
                eq(MinioDeleteTaskService.STATUS_PENDING),
                eq(2),
                any(Instant.class)
        )).thenReturn(2);
        when(fixture.repo.findTop50ByStatusOrderByCreatedAtAsc(MinioDeleteTaskService.STATUS_PENDING))
                .thenReturn(List.of(pending));

        List<String> ids = fixture.service.findPendingTaskIds();

        assertEquals(List.of("minio-del-1"), ids);
        InOrder order = inOrder(fixture.repo);
        order.verify(fixture.repo).resetStaleProcessing(
                eq(MinioDeleteTaskService.STATUS_PROCESSING),
                eq(MinioDeleteTaskService.STATUS_PENDING),
                any(Instant.class),
                any(Instant.class)
        );
        order.verify(fixture.repo).resetFailedForRetry(
                eq(MinioDeleteTaskService.STATUS_FAILED),
                eq(MinioDeleteTaskService.STATUS_PENDING),
                eq(2),
                any(Instant.class)
        );
        order.verify(fixture.repo)
                .findTop50ByStatusOrderByCreatedAtAsc(MinioDeleteTaskService.STATUS_PENDING);
    }

    @Test
    void treatsMissingMinioObjectAsSuccessfulIdempotentDelete() throws Exception {
        Fixture fixture = new Fixture();
        MinioDeleteTask task = new MinioDeleteTask();
        task.setId("minio-del-1");
        task.setBucket("models");
        task.setObjectName("missing/object.bin");
        task.setStatus(MinioDeleteTaskService.STATUS_PENDING);
        task.setRetryCount(4);
        task.setMaxRetryCount(5);
        when(fixture.repo.claimPending(
                eq(task.getId()),
                eq(MinioDeleteTaskService.STATUS_PENDING),
                eq(MinioDeleteTaskService.STATUS_PROCESSING),
                any(Instant.class)
        )).thenReturn(1);
        when(fixture.repo.findById(task.getId())).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("Object does not exist"))
                .when(fixture.minioService)
                .deleteObject(task.getBucket(), task.getObjectName());

        fixture.service.processPendingTask(task.getId());

        ArgumentCaptor<MinioDeleteTask> saved = ArgumentCaptor.forClass(MinioDeleteTask.class);
        verify(fixture.repo).save(saved.capture());
        assertEquals(MinioDeleteTaskService.STATUS_SUCCESS, saved.getValue().getStatus());
        assertEquals(4, saved.getValue().getRetryCount());
    }

    private static final class Fixture {
        private final MinioDeleteTaskRepository repo = mock(MinioDeleteTaskRepository.class);
        private final MinioService minioService = mock(MinioService.class);
        private final MinioDeleteTaskService service;

        private Fixture() {
            MinioConfig minioConfig = new MinioConfig();
            minioConfig.setBucket("models");
            service = new MinioDeleteTaskService(
                    repo,
                    minioService,
                    minioConfig,
                    new NoOpTransactionManager()
            );
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
