package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCachePolicyKubernetesClientTest {

    private final TrainingEnvironmentService environmentService =
            mock(TrainingEnvironmentService.class);
    private final ShellCommandRunner shellRunner = mock(ShellCommandRunner.class);
    private final TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
    private final ModelCachePolicyKubernetesClient client = new ModelCachePolicyKubernetesClient(
            environmentService, shellRunner, new ObjectMapper(), properties
    );
    private final Path kubeconfig = Path.of("k8s/config");
    private final Path root = Path.of(".");

    @BeforeEach
    void setUp() {
        properties.setNamespace("tss-training");
        when(environmentService.resolveKubeconfig()).thenReturn(kubeconfig);
        when(environmentService.resolveProjectRoot()).thenReturn(root);
        when(environmentService.kubectlCommand(eq(kubeconfig), any(String[].class)))
                .thenReturn(List.of("kubectl"));
    }

    @Test
    void parsesStrictPositiveCapacityTuple() {
        ModelCachePolicyKubernetesClient.PolicySnapshot snapshot = client.parse("""
                {"data":{"maxBytes":"1073741824","minFreeBytes":"3221225472",
                "runtimeReserveBytes":"10737418240"}}
                """);

        assertTrue(snapshot.present());
        assertEquals(1073741824L, snapshot.maxBytes());
        assertEquals(3221225472L, snapshot.minFreeBytes());
        assertEquals(10737418240L, snapshot.runtimeReserveBytes());
    }

    @Test
    void missingConfigMapIsAnExplicitAbsentSnapshot() {
        when(shellRunner.run(anyList(), eq(root), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.failed(
                        1, "Error from server (NotFound): configmaps not found", "command failed"
                )
        );

        assertFalse(client.read().present());
    }

    @Test
    void writesAllThreeValuesAndVerifiesReadBack() {
        long gib = 1024L * 1024 * 1024;
        when(shellRunner.runWithInput(anyList(), eq(root), any(), eq(30)))
                .thenReturn(ShellCommandRunner.CommandResult.success("configured"));
        when(shellRunner.run(anyList(), eq(root), eq(30))).thenReturn(
                ShellCommandRunner.CommandResult.success("""
                        {"data":{"maxBytes":"1073741824","minFreeBytes":"3221225472",
                        "runtimeReserveBytes":"10737418240"}}
                        """)
        );

        client.writeAndReadBack(new ModelCachePolicy(gib, 3 * gib, 10 * gib, null));

        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(shellRunner).runWithInput(anyList(), eq(root), yaml.capture(), eq(30));
        assertTrue(yaml.getValue().contains("maxBytes: \"1073741824\""));
        assertTrue(yaml.getValue().contains("minFreeBytes: \"3221225472\""));
        assertTrue(yaml.getValue().contains("runtimeReserveBytes: \"10737418240\""));
    }

    @Test
    void rejectsMalformedReadBackInsteadOfSilentlyUsingDefaults() {
        when(shellRunner.run(anyList(), eq(root), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.success(
                        "{\"data\":{\"maxBytes\":\"1\",\"minFreeBytes\":\"bad\",\"runtimeReserveBytes\":\"3\"}}"
                )
        );

        assertThrows(IllegalStateException.class, client::read);
    }

    @Test
    void rejectsPositiveButNonGiBConfigMapValues() {
        assertThrows(IllegalStateException.class, () -> client.parse("""
                {"data":{"maxBytes":"1073741825","minFreeBytes":"3221225472",
                "runtimeReserveBytes":"10737418240"}}
                """));
    }
}
