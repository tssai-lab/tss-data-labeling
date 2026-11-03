package com.tss.platform.inference;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InferenceExecutorRouterTest {

    private TrainingEnvironmentService environmentService;
    private KubernetesInferenceExecutor kubernetesExecutor;
    private InferenceTaskRepository taskRepository;
    private InferenceExecutorRouter router;
    private NoOpTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setEnabled(true);
        environmentService = mock(TrainingEnvironmentService.class);
        kubernetesExecutor = mock(KubernetesInferenceExecutor.class);
        taskRepository = mock(InferenceTaskRepository.class);
        transactionManager = new NoOpTransactionManager();
        router = new InferenceExecutorRouter(
                properties,
                environmentService,
                kubernetesExecutor,
                taskRepository,
                transactionManager
        );
    }

    @Test
    void startsOnlyAfterAtomicSubmissionClaim() {
        when(taskRepository.claimForSubmission(eq("task-1"), eq(2), any(Instant.class)))
                .thenReturn(1);
        when(environmentService.isKubernetesReady()).thenReturn(true);
        when(kubernetesExecutor.getType()).thenReturn("kubernetes");

        router.start("task-1", 2);

        verify(kubernetesExecutor).start("task-1", 2);
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionManager.lastPropagationBehavior);
    }

    @Test
    void duplicateSubmissionDoesNotStartAnotherWorker() {
        when(taskRepository.claimForSubmission(eq("task-1"), eq(2), any(Instant.class)))
                .thenReturn(0);

        router.start("task-1", 2);

        verify(environmentService, never()).isKubernetesReady();
        verify(kubernetesExecutor, never()).start(any(), any());
    }

    @Test
    void recoversStaleScheduledSubmissionUsingSameAttempt() {
        InferenceTask snapshot = task("task-1", "scheduled", 2);
        when(taskRepository.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any()))
                .thenReturn(List.of(snapshot));
        when(taskRepository.resetStaleSubmission(eq("task-1"), eq(2), any(), any()))
                .thenReturn(1);
        when(taskRepository.claimForSubmission(eq("task-1"), eq(2), any(Instant.class)))
                .thenReturn(1);
        when(environmentService.isKubernetesReady()).thenReturn(true);
        when(kubernetesExecutor.getType()).thenReturn("kubernetes");

        router.recoverStaleSubmissions();

        verify(taskRepository).resetStaleSubmission(eq("task-1"), eq(2), any(), any());
        verify(kubernetesExecutor).start("task-1", 2);
    }

    @Test
    void unavailableEnvironmentFailsOnlyTheClaimedAttempt() {
        InferenceTask current = task("task-1", "scheduled", 2);
        when(taskRepository.claimForSubmission(eq("task-1"), eq(2), any(Instant.class)))
                .thenReturn(1);
        when(environmentService.isKubernetesReady()).thenReturn(false);
        when(taskRepository.findByIdForUpdate("task-1")).thenReturn(Optional.of(current));

        router.start("task-1", 2);

        assertEquals("failed", current.getStatus());
        assertEquals(0, current.getProgress());
        verify(taskRepository).save(current);
        verify(kubernetesExecutor, never()).start(any(), any());
    }

    @Test
    void failedOldSubmissionCannotOverwriteNewAttempt() {
        InferenceTask current = task("task-1", "pending", 3);
        when(taskRepository.claimForSubmission(eq("task-1"), eq(2), any(Instant.class)))
                .thenReturn(1);
        when(environmentService.isKubernetesReady()).thenReturn(false);
        when(taskRepository.findByIdForUpdate("task-1")).thenReturn(Optional.of(current));

        router.start("task-1", 2);

        assertEquals("pending", current.getStatus());
        verify(taskRepository, never()).save(any());
    }

    private static InferenceTask task(String id, String status, int attempt) {
        InferenceTask task = new InferenceTask();
        task.setId(id);
        task.setStatus(status);
        task.setCurrentAttempt(attempt);
        task.setUpdatedAt(Instant.now().minusSeconds(600));
        return task;
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        private int lastPropagationBehavior = -1;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lastPropagationBehavior = definition.getPropagationBehavior();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No-op.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No-op.
        }
    }
}
