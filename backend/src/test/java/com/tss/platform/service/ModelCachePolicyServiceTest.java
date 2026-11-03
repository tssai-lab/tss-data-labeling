package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.dto.modelcache.ModelCacheDtos;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.module1.service.OperationLogService;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCachePolicyServiceTest {

    private static final long GIB = PlatformSystemConfig.GIB;

    private final PlatformSystemConfigRepository repository =
            mock(PlatformSystemConfigRepository.class);
    private final InferenceModelCacheProperties properties = new InferenceModelCacheProperties();
    private final AuthContext authContext = mock(AuthContext.class);
    private final ModelCacheAdministrationService administrationService =
            mock(ModelCacheAdministrationService.class);
    private final ModelCachePolicyKubernetesClient kubernetesClient =
            mock(ModelCachePolicyKubernetesClient.class);
    private final OperationLogService operationLogService = mock(OperationLogService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final ModelCachePolicyService service = new ModelCachePolicyService(
            repository,
            properties,
            authContext,
            administrationService,
            kubernetesClient,
            operationLogService,
            transactionManager
    );

    @BeforeEach
    void setUp() {
        when(authContext.isSuperAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(1);
        when(authContext.currentUsername()).thenReturn("admin");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(operationLogService.recordLog(any())).thenReturn(true);
        when(repository.saveAndFlush(any(PlatformSystemConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(kubernetesClient.read()).thenReturn(
                new ModelCachePolicyKubernetesClient.PolicySnapshot(true, 8 * GIB, 5 * GIB, 10 * GIB)
        );
        when(kubernetesClient.writeAndReadBack(any())).thenAnswer(invocation -> {
            ModelCachePolicy policy = invocation.getArgument(0);
            return new ModelCachePolicyKubernetesClient.PolicySnapshot(
                    true, policy.maxBytes(), policy.minFreeBytes(), policy.runtimeReserveBytes()
            );
        });
    }

    @Test
    void updatesAllThreeValuesAfterValidatingEveryReadyNode() {
        PlatformSystemConfig config = config(8, 5, 10);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(administrationService.inspectPolicyNodes()).thenReturn(List.of(
                node("k8s-master", 0, 25 * GIB, null),
                node("k8s-node1", GIB / 2, 50 * GIB, null)
        ));

        ModelCachePolicy result = service.updateForSuperAdministration(
                new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
        );

        assertEquals(GIB, result.maxBytes());
        assertEquals(3 * GIB, result.minFreeBytes());
        assertEquals(10 * GIB, result.runtimeReserveBytes());
        assertEquals(GIB, config.getModelCacheMaxBytes());
        verify(kubernetesClient).writeAndReadBack(any(ModelCachePolicy.class));
        verify(repository).saveAndFlush(config);
        verify(operationLogService).recordLog(any());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void rejectsCacheLimitBelowCurrentUsageWithoutChangingClusterOrDatabase() {
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config(8, 5, 10)));
        when(administrationService.inspectPolicyNodes()).thenReturn(List.of(
                node("k8s-master", 2 * GIB, 25 * GIB, null)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
                )
        );

        assertTrue(error.getMessage().contains("请先清理缓存"));
        verify(kubernetesClient, never()).writeAndReadBack(any());
        verify(repository, never()).saveAndFlush(any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void rejectsPolicyWhenAnyReadyNodeCannotMeetTheNewGate() {
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config(8, 5, 10)));
        when(administrationService.inspectPolicyNodes()).thenReturn(List.of(
                node("k8s-master", 0, 13 * GIB, null)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
                )
        );

        assertTrue(error.getMessage().contains("新保护门需要"));
        verify(kubernetesClient, never()).writeAndReadBack(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPolicyWhenAnyReadyNodeCannotBeInspected() {
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config(8, 5, 10)));
        when(administrationService.inspectPolicyNodes()).thenReturn(List.of(
                node("k8s-master", 0, 0, "worker probe timed out")
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
                )
        );

        assertTrue(error.getMessage().contains("worker probe timed out"));
        verify(kubernetesClient, never()).writeAndReadBack(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void restoresConfigMapWhenDatabaseWriteFails() {
        PlatformSystemConfig config = config(8, 5, 10);
        ModelCachePolicyKubernetesClient.PolicySnapshot before =
                new ModelCachePolicyKubernetesClient.PolicySnapshot(true, 8 * GIB, 5 * GIB, 10 * GIB);
        when(repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID))
                .thenReturn(Optional.of(config));
        when(administrationService.inspectPolicyNodes()).thenReturn(List.of(
                node("k8s-master", 0, 25 * GIB, null)
        ));
        when(kubernetesClient.read()).thenReturn(before);
        when(repository.saveAndFlush(config)).thenThrow(new IllegalStateException("db unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
                )
        );

        verify(kubernetesClient).restore(before);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void rejectsNonGiBValuesAndNonSuperAdministratorsBeforeInspection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB + 1, 3 * GIB, 10 * GIB)
                )
        );
        verify(administrationService, never()).inspectPolicyNodes();

        when(authContext.isSuperAdmin()).thenReturn(false);
        assertThrows(
                CodeApprovalForbiddenException.class,
                () -> service.updateForSuperAdministration(
                        new ModelCacheDtos.PolicyUpdateRequest(GIB, 3 * GIB, 10 * GIB)
                )
        );
    }

    private PlatformSystemConfig config(long maxGiB, long minFreeGiB, long reserveGiB) {
        PlatformSystemConfig config = new PlatformSystemConfig();
        config.setId(PlatformSystemConfig.GLOBAL_ID);
        config.setModelCacheMaxBytes(maxGiB * GIB);
        config.setModelCacheMinFreeBytes(minFreeGiB * GIB);
        config.setModelCacheRuntimeReserveBytes(reserveGiB * GIB);
        config.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        config.setUpdatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return config;
    }

    private ModelCacheDtos.Node node(String name, long used, long free, String error) {
        return new ModelCacheDtos.Node(
                name, name, name, true,
                used, free, 80 * GIB, 0, 0, List.of(), error
        );
    }
}
