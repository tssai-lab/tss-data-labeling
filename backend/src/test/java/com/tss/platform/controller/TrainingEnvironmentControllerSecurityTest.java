package com.tss.platform.controller;

import com.tss.platform.security.AuthContext;
import com.tss.platform.training.TrainingEnvironmentService;
import com.tss.platform.training.TrainingEnvironmentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingEnvironmentControllerSecurityTest {

    @Test
    void ordinaryUserOnlyReceivesReadinessInformation() {
        TrainingEnvironmentService service = mock(TrainingEnvironmentService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(service.getStatus()).thenReturn(status());
        when(authContext.isSuperAdmin()).thenReturn(false);

        var body = new TrainingEnvironmentController(service, authContext).status().getData();

        assertThat(body).containsEntry("state", "DEGRADED");
        assertThat(body).containsEntry("kubernetesReady", false);
        assertThat(body).containsEntry("message", "K8s 环境部分可用");
        assertThat(body).doesNotContainKeys(
                "clusterName", "namespace", "workerImage", "kubeconfig", "lastError");
    }

    @Test
    void superAdministratorRetainsDiagnosticFields() {
        TrainingEnvironmentService service = mock(TrainingEnvironmentService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(service.getStatus()).thenReturn(status());
        when(authContext.isSuperAdmin()).thenReturn(true);

        var body = new TrainingEnvironmentController(service, authContext).status().getData();

        assertThat(body).containsEntry("clusterName", "kind-tss")
                .containsEntry("namespace", "tss-platform")
                .containsEntry("workerImage", "private/worker:sha")
                .containsEntry("kubeconfig", "C:/secret/admin.conf")
                .containsEntry("lastError", "connection refused");
    }

    private static TrainingEnvironmentStatus status() {
        TrainingEnvironmentStatus status = new TrainingEnvironmentStatus();
        status.setState(TrainingEnvironmentStatus.State.DEGRADED);
        status.setKubernetesEnabled(true);
        status.setKubernetesReady(false);
        status.setFallbackToLocal(false);
        status.setClusterName("kind-tss");
        status.setNamespace("tss-platform");
        status.setWorkerImage("private/worker:sha");
        status.setKubeconfig("C:/secret/admin.conf");
        status.setMessage("K8s 环境部分可用");
        status.setLastError("connection refused");
        status.setCheckedAt(Instant.parse("2026-08-25T00:00:00Z"));
        return status;
    }
}
