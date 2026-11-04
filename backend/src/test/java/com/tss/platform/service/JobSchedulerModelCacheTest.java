package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.training.TrainingExecutorRouter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSchedulerModelCacheTest {

    @Test
    void cacheEnabledExcludesNodesWithoutReadyLabel() {
        Fixture fixture = new Fixture();
        ComputeServer unready = node("10.0.0.1", false);
        ComputeServer ready = node("10.0.0.2", true);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(unready, ready));

        String assigned = fixture.scheduler.assignNodeForInference(new InferenceTask(), "a".repeat(64));

        assertEquals("10.0.0.2", assigned);
        assertFalse(fixture.scheduler.isCacheReady(unready));
        assertTrue(fixture.scheduler.isCacheReady(ready));
    }

    @Test
    void sameDigestUsesStableRendezvousAffinityAcrossReadyNodes() {
        Fixture fixture = new Fixture();
        ComputeServer first = node("10.0.0.1", true);
        ComputeServer second = node("10.0.0.2", true);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(first, second));
        String digest = "b".repeat(64);
        String expected = Long.compareUnsigned(
                JobScheduler.affinityScore(digest, first.getServerIp()),
                JobScheduler.affinityScore(digest, second.getServerIp())) > 0
                ? first.getServerIp() : second.getServerIp();

        assertEquals(expected, fixture.scheduler.assignNodeForInference(new InferenceTask(), digest));
        assertEquals(expected, fixture.scheduler.assignNodeForInference(new InferenceTask(), digest));
    }

    @Test
    void cacheDisabledKeepsExistingResourceFirstScheduling() {
        Fixture fixture = new Fixture(false);
        ComputeServer smaller = node("10.0.0.1", false);
        smaller.setCpuCores(2.0);
        smaller.setMemoryGib(2.0);
        ComputeServer larger = node("10.0.0.2", false);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(smaller, larger));
        String digest = null;
        for (int index = 0; index < 100; index++) {
            String candidate = String.format("%064x", index);
            if (Long.compareUnsigned(
                    JobScheduler.affinityScore(candidate, smaller.getServerIp()),
                    JobScheduler.affinityScore(candidate, larger.getServerIp())) > 0) {
                digest = candidate;
                break;
            }
        }

        assertNotNull(digest);
        assertEquals(larger.getServerIp(),
                fixture.scheduler.assignNodeForInference(new InferenceTask(), digest));
    }

    @Test
    void gpuSelectorFailsClosedWhenNodeLabelsAreMissingOrMalformed() {
        Fixture fixture = new Fixture(false);
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":1}}
                """);
        ComputeServer missing = gpuNode("10.0.0.1", null);
        ComputeServer malformed = gpuNode("10.0.0.2", "not-json");
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(missing, malformed));

        assertNull(fixture.scheduler.assignNodeForTraining(
                task, Map.of("tss.ai/accelerator", "nvidia")));

        ComputeServer matching = gpuNode(
                "10.0.0.3", "{\"tss.ai/accelerator\":\"nvidia\",\"tss.ai/node-pool\":\"cpu\"}");
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(missing, malformed, matching));
        assertEquals("10.0.0.3", fixture.scheduler.assignNodeForTraining(
                task, Map.of("tss.ai/accelerator", "nvidia")));
    }

    @Test
    void cpuSchedulingKeepsHistoricalFallbackWhenMetricsLabelsAreTemporarilyMissing() {
        Fixture fixture = new Fixture(false);
        ComputeServer cpu = node("10.0.0.4", false);
        cpu.setK8sLabelsJson(null);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(cpu));

        assertEquals("10.0.0.4",
                fixture.scheduler.assignNodeForInference(new InferenceTask(), null));
    }

    @Test
    void explicitlyUnschedulableNodeIsExcludedWithoutUsingHostNamesOrRoles() {
        Fixture fixture = new Fixture(false);
        ComputeServer blocked = gpuNode("10.0.0.10", """
                {"tss.ai/accelerator":"nvidia","tss.ai/platform-schedulable":"false"}
                """);
        ComputeServer worker = gpuNode("10.0.0.11", """
                {"tss.ai/accelerator":"nvidia","tss.ai/platform-schedulable":"true"}
                """);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(blocked, worker));
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":1}}
                """);

        assertEquals("10.0.0.11", fixture.scheduler.assignNodeForTraining(
                task, Map.of("tss.ai/accelerator", "nvidia")));
        assertFalse(fixture.scheduler.isPlatformSchedulable(blocked));
        assertTrue(fixture.scheduler.isPlatformSchedulable(worker));
    }

    @Test
    void disabledNodeIsNeverSelectedForInference() {
        Fixture fixture = new Fixture(false);
        ComputeServer disabled = node("10.0.0.1", false);
        disabled.setEnabled(false);
        ComputeServer enabled = node("10.0.0.2", false);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(disabled, enabled));

        assertEquals(enabled.getServerIp(),
                fixture.scheduler.assignNodeForInference(new InferenceTask(), null));
    }

    @Test
    void cpuOnlyFallbackCanRunCpuButNeverReceivesGpuOverflow() {
        Fixture fixture = new Fixture(false);
        ComputeServer control = gpuNode("tss-ai-control-01", """
                {"node-role.kubernetes.io/control-plane":"","tss.ai/node-pool":"cpu","tss.ai/accelerator":"nvidia","tss.ai/gpu-schedulable":"false","tss.ai/platform-schedulable":"true","tss.ai/platform-max-active-tasks":"1"}
                """);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(control));

        TrainingExperimentVersion cpuTask = new TrainingExperimentVersion();
        cpuTask.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":0}}
                """);
        assertEquals("tss-ai-control-01", fixture.scheduler.assignNodeForTraining(
                cpuTask, Map.of("tss.ai/node-pool", "cpu")));

        TrainingExperimentVersion gpuTask = new TrainingExperimentVersion();
        gpuTask.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":1}}
                """);
        assertNull(fixture.scheduler.assignNodeForTraining(
                gpuTask, Map.of("tss.ai/accelerator", "nvidia")));
        assertNull(fixture.scheduler.assignNodeForTraining(
                gpuTask, Map.of("tss.ai/node-pool", "cpu")));
    }

    @Test
    void perNodeActiveTaskCapProtectsCpuFallback() {
        Fixture fixture = new Fixture(false);
        ComputeServer control = node("tss-ai-control-01", false);
        control.setK8sLabelsJson("""
                {"tss.ai/node-pool":"cpu","tss.ai/platform-schedulable":"true","tss.ai/platform-max-active-tasks":"1"}
                """);
        TrainingExperimentVersion existing = new TrainingExperimentVersion();
        existing.setServerIp("tss-ai-control-01");
        existing.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":0}}
                """);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(control));
        when(fixture.trainingTasks.findByStatus("scheduled")).thenReturn(List.of(existing));

        TrainingExperimentVersion next = new TrainingExperimentVersion();
        next.setRunSpecJson("""
                {"resources":{"cpuRequest":"1","memoryRequest":"1Gi","gpuCount":0}}
                """);
        assertNull(fixture.scheduler.assignNodeForTraining(
                next, Map.of("tss.ai/node-pool", "cpu")));
    }

    @Test
    void missingSchedulableMarkerPreservesExistingCpuClusterBehavior() {
        Fixture fixture = new Fixture(false);
        ComputeServer existing = node("10.0.0.12", false);
        when(fixture.computeServers.findByDeletedFalse()).thenReturn(List.of(existing));

        assertEquals("10.0.0.12",
                fixture.scheduler.assignNodeForInference(new InferenceTask(), null));
        assertTrue(fixture.scheduler.isPlatformSchedulable(existing));
    }

    private static ComputeServer node(String serverIp, boolean cacheReady) {
        ComputeServer node = new ComputeServer();
        node.setServerIp(serverIp);
        node.setStatus("online");
        node.setEnabled(true);
        node.setDeleted(false);
        node.setCpuCores(8.0);
        node.setMemoryGib(16.0);
        node.setGpuCount(0);
        node.setK8sLabelsJson("{\"tss.ai/node-pool\":\"cpu\",\"tss.ai/model-cache-ready\":\""
                + cacheReady + "\"}");
        return node;
    }

    private static ComputeServer gpuNode(String serverIp, String labelsJson) {
        ComputeServer node = node(serverIp, false);
        node.setGpuCount(2);
        node.setK8sLabelsJson(labelsJson);
        return node;
    }

    private static final class Fixture {
        private final ComputeServerRepository computeServers = mock(ComputeServerRepository.class);
        private final TrainingExperimentVersionRepository trainingTasks =
                mock(TrainingExperimentVersionRepository.class);
        private final InferenceTaskRepository inferenceTasks = mock(InferenceTaskRepository.class);
        private final JobScheduler scheduler;

        private Fixture() {
            this(true);
        }

        private Fixture(boolean cacheEnabled) {
            when(trainingTasks.findByStatus("running")).thenReturn(List.of());
            when(trainingTasks.findByStatus("scheduled")).thenReturn(List.of());
            when(inferenceTasks.findByStatus("running")).thenReturn(List.of());
            when(inferenceTasks.findByStatus("scheduled")).thenReturn(List.of());
            scheduler = new JobScheduler(
                    computeServers,
                    trainingTasks,
                    inferenceTasks,
                    new TrainingKubernetesProperties(),
                    mock(TrainingExecutorRouter.class),
                    mock(TransactionTemplate.class)
            );
            InferenceModelCacheProperties properties = new InferenceModelCacheProperties();
            properties.setEnabled(cacheEnabled);
            scheduler.setModelCacheProperties(properties);
        }
    }
}
