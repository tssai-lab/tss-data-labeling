package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.modelcache.ModelCacheDtos;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCacheAdministrationServiceTest {

    private final InferenceModelCacheProperties cacheProperties =
            new InferenceModelCacheProperties();
    private final TrainingKubernetesProperties kubernetesProperties =
            new TrainingKubernetesProperties();
    private final TrainingEnvironmentService environmentService =
            mock(TrainingEnvironmentService.class);
    private final ShellCommandRunner commandRunner = mock(ShellCommandRunner.class);
    private final ComputeServerRepository serverRepository = mock(ComputeServerRepository.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final AuditRecordService auditRecordService = mock(AuditRecordService.class);
    private ModelCacheAdministrationService service;

    @BeforeEach
    void setUp() {
        kubernetesProperties.setNamespace("tss-training");
        kubernetesProperties.setServiceAccount("tss-training-worker");
        service = new ModelCacheAdministrationService(
                cacheProperties,
                kubernetesProperties,
                environmentService,
                commandRunner,
                serverRepository,
                new ObjectMapper(),
                authContext,
                auditRecordService
        );
        ReflectionTestUtils.setField(service, "workerImage", "tss-inference-worker:test");
        ReflectionTestUtils.setField(service, "workerImagePullPolicy", "IfNotPresent");
    }

    @Test
    void cacheDefaultsAreSafeForTheKubeadmPhysicalNode() {
        assertEquals("/opt/tss-platform/model-cache", cacheProperties.getNodePath());
        assertEquals(1024L * 1024 * 1024, cacheProperties.getMaxBytes());
        assertEquals(3L * 1024 * 1024 * 1024, cacheProperties.getMinFreeBytes());
        assertEquals(10L * 1024 * 1024 * 1024, cacheProperties.getRuntimeReserveBytes());
    }

    @Test
    void normalAdministratorCanInspectButCannotClear() {
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.isSuperAdmin()).thenReturn(false);
        when(serverRepository.findByDeletedFalse()).thenReturn(List.of());

        ModelCacheDtos.Overview overview = service.overview();

        assertTrue(overview.nodes().isEmpty());
        assertThrows(
                CodeApprovalForbiddenException.class,
                () -> service.clear(validClearRequest())
        );
        verify(commandRunner, never()).runWithInput(anyList(), any(), any(), anyInt());
    }

    @Test
    void overviewCalculatesTheDynamicGateAndPerNodeHeadroom() {
        cacheProperties.setEnabled(true);
        when(authContext.isAdmin()).thenReturn(true);
        ComputeServer server = cacheReadyServer();
        when(serverRepository.findByDeletedFalse()).thenReturn(List.of(server));
        Path kubeconfig = Path.of("k8s/.kube/config");
        Path root = Path.of(".");
        when(environmentService.resolveKubeconfig()).thenReturn(kubeconfig);
        when(environmentService.resolveProjectRoot()).thenReturn(root);
        when(environmentService.kubectlCommand(eq(kubeconfig), any(String[].class)))
                .thenReturn(List.of("kubectl"));
        when(commandRunner.runWithInput(anyList(), eq(root), any(), eq(30)))
                .thenReturn(ShellCommandRunner.CommandResult.success("pod created"));
        when(commandRunner.run(anyList(), eq(root), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.success("condition met"),
                ShellCommandRunner.CommandResult.success(
                        "MODEL_CACHE_RESULT_JSON={\"usedBytes\":0,\"diskFreeBytes\":26306674688,"
                                + "\"diskTotalBytes\":84161257472,\"entries\":[]}\n"
                ),
                ShellCommandRunner.CommandResult.success("deleted")
        );
        long gib = 1024L * 1024 * 1024;

        ModelCacheDtos.Overview overview = service.overview(
                new ModelCachePolicy(gib, 3 * gib, 10 * gib, null)
        );

        assertEquals(14 * gib, overview.emptyCacheGateBytes());
        assertEquals(10 * gib, overview.runtimeReserveBytes());
        assertEquals(14 * gib, overview.nodes().get(0).requiredAvailableBytes());
        assertEquals(26306674688L - 14 * gib, overview.nodes().get(0).policyHeadroomBytes());
    }

    @Test
    void superAdministratorCanClearSelectedDigestOnCacheReadyNode() {
        cacheProperties.setEnabled(true);
        cacheProperties.setNodePath("/var/lib/tss-platform/model-cache");
        cacheProperties.setMountPath("/var/cache/tss/models");
        when(authContext.isSuperAdmin()).thenReturn(true);

        ComputeServer server = cacheReadyServer();
        when(serverRepository.findByServerIpAndDeletedFalse("node-1"))
                .thenReturn(Optional.of(server));
        Path kubeconfig = Path.of("k8s/.kube/config");
        Path root = Path.of(".");
        when(environmentService.resolveKubeconfig()).thenReturn(kubeconfig);
        when(environmentService.resolveProjectRoot()).thenReturn(root);
        when(environmentService.kubectlCommand(eq(kubeconfig), any(String[].class)))
                .thenReturn(List.of("kubectl"));
        when(commandRunner.runWithInput(anyList(), eq(root), any(), eq(30)))
                .thenReturn(ShellCommandRunner.CommandResult.success("pod created"));
        String digest = "a".repeat(64);
        when(commandRunner.run(anyList(), eq(root), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.success("condition met"),
                ShellCommandRunner.CommandResult.success(
                        "MODEL_CACHE_RESULT_JSON={\"cleared\":[\"" + digest
                                + "\"],\"inUse\":[],\"notFound\":[]}\n"),
                ShellCommandRunner.CommandResult.success("deleted")
        );

        ModelCacheDtos.ClearRequest request = validClearRequest();
        ModelCacheDtos.ClearResponse response = service.clear(request);

        assertEquals(List.of(digest), response.nodes().get(0).cleared());
        assertTrue(response.nodes().get(0).inUse().isEmpty());
        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(commandRunner).runWithInput(anyList(), eq(root), yaml.capture(), eq(30));
        assertTrue(yaml.getValue().contains("cpu: \"100m\""));
        assertTrue(yaml.getValue().contains("memory: \"128Mi\""));
        assertTrue(yaml.getValue().contains("persistentVolumeClaim:"));
        assertTrue(yaml.getValue().contains("claimName: \"tss-model-cache-kind-worker\""));
        assertTrue(!yaml.getValue().contains("hostPath:"));
        verify(auditRecordService).recordSuccess(
                any(), any(), eq("model-cache"), any());
    }

    @Test
    void clearRejectsInvalidDigestBeforeStartingPod() {
        cacheProperties.setEnabled(true);
        when(authContext.isSuperAdmin()).thenReturn(true);
        ModelCacheDtos.ClearRequest request = new ModelCacheDtos.ClearRequest();
        request.setServerIps(List.of("node-1"));
        request.setSha256s(List.of("../escape"));

        assertThrows(IllegalArgumentException.class, () -> service.clear(request));

        verify(commandRunner, never()).runWithInput(anyList(), any(), any(), anyInt());
    }

    private ModelCacheDtos.ClearRequest validClearRequest() {
        ModelCacheDtos.ClearRequest request = new ModelCacheDtos.ClearRequest();
        request.setServerIps(List.of("node-1"));
        request.setSha256s(List.of("a".repeat(64)));
        return request;
    }

    private ComputeServer cacheReadyServer() {
        ComputeServer server = new ComputeServer();
        server.setServerIp("node-1");
        server.setHostname("node-1");
        server.setK8sNodeName("kind-worker");
        server.setEnabled(true);
        server.setK8sLabelsJson("{\"tss.ai/model-cache-ready\":\"true\"}");
        return server;
    }
}
