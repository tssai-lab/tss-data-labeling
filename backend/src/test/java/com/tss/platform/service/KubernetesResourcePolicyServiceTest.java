package com.tss.platform.service;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.KubernetesResourcePolicyDto;
import com.tss.platform.dto.KubernetesResourcePolicyUpdateRequest;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.module1.service.OperationLogService;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesResourcePolicyServiceTest {

    private final PlatformSystemConfigRepository repository =
            mock(PlatformSystemConfigRepository.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final KubernetesResourceQuotaClient quotaClient = mock(KubernetesResourceQuotaClient.class);
    private final TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
    private final OperationLogService operationLogService = mock(OperationLogService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final KubernetesResourcePolicyService service = new KubernetesResourcePolicyService(
            repository,
            authContext,
            quotaClient,
            properties,
            operationLogService,
            transactionManager
    );

    @BeforeEach
    void setUp() {
        properties.setClusterName("main-cluster");
        properties.setNamespace("tss-training");
        when(authContext.isSuperAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(9);
        when(authContext.currentUsername()).thenReturn("root-admin");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(repository.saveAndFlush(any(PlatformSystemConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(operationLogService.recordLog(any())).thenReturn(true);
    }

    @Test
    void updatesQuotaThenDatabaseAndReturnsReadBackUsage() {
        PlatformSystemConfig config = config(10, 20, 3600);
        KubernetesResourceQuotaClient.QuotaSnapshot before = snapshot(10, 20, 1, 1);
        KubernetesResourceQuotaClient.QuotaSnapshot after = snapshot(8, 15, 1, 1);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(quotaClient.read()).thenReturn(before);
        when(quotaClient.writeAndReadBack(8, 15)).thenReturn(after);

        KubernetesResourcePolicyDto result = service.updateForSuperAdministration(
                new KubernetesResourcePolicyUpdateRequest(8, 15, 180)
        );

        assertEquals(8, result.podQuota());
        assertEquals(15, result.jobQuota());
        assertEquals(180, result.jobTtlSecondsAfterFinished());
        assertEquals(8, config.getPodQuota());
        assertEquals(15, config.getJobQuota());
        assertEquals(180, config.getJobTtlSecondsAfterFinished());
        verify(repository).saveAndFlush(config);
        verify(transactionManager).commit(transactionStatus);
        verify(operationLogService).recordLog(any());
    }

    @Test
    void rejectsQuotaBelowCurrentUsageWithoutDeletingOrWritingAnything() {
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config(10, 20, 3600)));
        when(quotaClient.read()).thenReturn(snapshot(10, 20, 7, 3));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(6, 20, 180)
                )
        );

        assertTrue(error.getMessage().contains("当前占用 7"));
        verify(quotaClient, never()).writeAndReadBack(anyInt(), anyInt());
        verify(repository, never()).saveAndFlush(any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void restoresKubernetesQuotaWhenDatabaseWriteFails() {
        PlatformSystemConfig config = config(10, 20, 3600);
        KubernetesResourceQuotaClient.QuotaSnapshot before = snapshot(10, 20, 1, 1);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(quotaClient.read()).thenReturn(before);
        when(quotaClient.writeAndReadBack(8, 15)).thenReturn(snapshot(8, 15, 1, 1));
        when(quotaClient.writeAndReadBack(10, 20)).thenReturn(before);
        when(repository.saveAndFlush(config)).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(8, 15, 180)
                )
        );

        verify(quotaClient).writeAndReadBack(8, 15);
        verify(quotaClient).writeAndReadBack(10, 20);
        verify(transactionManager).rollback(transactionStatus);
        verify(operationLogService, atLeastOnce()).recordLog(any());
    }

    @Test
    void reportsHighVisibilityErrorWhenAutomaticRestoreAlsoFails() {
        PlatformSystemConfig config = config(10, 20, 3600);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(quotaClient.read()).thenReturn(snapshot(10, 20, 1, 1));
        when(quotaClient.writeAndReadBack(8, 15)).thenReturn(snapshot(8, 15, 1, 1));
        when(quotaClient.writeAndReadBack(10, 20))
                .thenThrow(new IllegalStateException("restore failed"));
        when(repository.saveAndFlush(config)).thenThrow(new IllegalStateException("database unavailable"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(8, 15, 180)
                )
        );

        assertTrue(error.getMessage().contains("自动恢复失败"));
    }

    @Test
    void repeatedIdenticalRequestIsIdempotent() {
        PlatformSystemConfig config = config(10, 20, 180);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(quotaClient.read()).thenReturn(snapshot(10, 20, 1, 1));

        KubernetesResourcePolicyDto result = service.updateForSuperAdministration(
                new KubernetesResourcePolicyUpdateRequest(10, 20, 180)
        );

        assertEquals(10, result.podQuota());
        verify(quotaClient, never()).writeAndReadBack(anyInt(), anyInt());
        verify(repository, never()).saveAndFlush(any());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void normalAdministratorCannotReadOrMutateResourcePolicy() {
        when(authContext.isSuperAdmin()).thenReturn(false);

        assertThrows(CodeApprovalForbiddenException.class, service::getForSuperAdministration);
        assertThrows(
                CodeApprovalForbiddenException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(10, 20, 180)
                )
        );
        verify(repository, never()).findById(any());
        verify(repository, never()).findByIdForUpdate(any());
        verify(quotaClient, never()).read();
    }

    @Test
    void invalidAndEmptyValuesFailBeforeClusterAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(null, 20, 180)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(0, 20, 180)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(10, 201, 180)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new KubernetesResourcePolicyUpdateRequest(10, 20, 59)
                )
        );
        verify(quotaClient, never()).read();
    }

    private static PlatformSystemConfig config(int pods, int jobs, int ttl) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        PlatformSystemConfig config = new PlatformSystemConfig();
        config.setId(PlatformSystemConfig.GLOBAL_ID);
        config.setPodQuota(pods);
        config.setJobQuota(jobs);
        config.setJobTtlSecondsAfterFinished(ttl);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        return config;
    }

    private static KubernetesResourceQuotaClient.QuotaSnapshot snapshot(
            int pods,
            int jobs,
            int usedPods,
            int usedJobs
    ) {
        return new KubernetesResourceQuotaClient.QuotaSnapshot(pods, jobs, usedPods, usedJobs);
    }
}
