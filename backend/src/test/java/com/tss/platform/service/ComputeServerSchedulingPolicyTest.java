package com.tss.platform.service;

import com.tss.platform.entity.ComputeServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComputeServerSchedulingPolicyTest {

    @Test
    void matchesHostnameAgainstStoredKubernetesNodeName() {
        ComputeServer server = new ComputeServer();
        server.setK8sNodeName("tss-ai-worker-01");
        server.setK8sLabelsJson("{\"tss.ai/platform-schedulable\":\"true\"}");

        assertThat(ComputeServerSchedulingPolicy.matchesNodeSelector(
                server,
                Map.of("kubernetes.io/hostname", "tss-ai-worker-01")
        )).isTrue();
    }

    @Test
    void rejectsDifferentKubernetesNodeName() {
        ComputeServer server = new ComputeServer();
        server.setK8sNodeName("tss-ai-worker-01");
        server.setK8sLabelsJson("{broken");

        assertThat(ComputeServerSchedulingPolicy.matchesNodeSelector(
                server,
                Map.of("kubernetes.io/hostname", "tss-ai-control-01")
        )).isFalse();
    }

    @Test
    void matchesExactHostnameEvenWhenUnrelatedStoredLabelsAreMalformed() {
        ComputeServer server = new ComputeServer();
        server.setK8sNodeName("tss-ai-worker-01");
        server.setK8sLabelsJson("{broken");

        assertThat(ComputeServerSchedulingPolicy.matchesNodeSelector(
                server,
                Map.of("kubernetes.io/hostname", "tss-ai-worker-01")
        )).isTrue();
    }
}
