package com.tss.platform.controller;

import com.tss.platform.dto.resource.KubernetesDiagnosticsDto;
import com.tss.platform.dto.resource.AddServerRequest;
import com.tss.platform.dto.resource.QueuedTask;
import com.tss.platform.dto.resource.PriorityRequest;
import com.tss.platform.dto.resource.ReorderRequest;
import com.tss.platform.dto.resource.RunningTask;
import com.tss.platform.dto.resource.ServerItem;
import com.tss.platform.dto.resource.UpdateServerEnabledRequest;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ResourceMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

class ResourceMonitorControllerSecurityTest {

    @Test
    void superAdminCanReadKubernetesDiagnostics() {
        ResourceMonitorService service = mock(ResourceMonitorService.class);
        AuthContext authContext = mock(AuthContext.class);
        KubernetesDiagnosticsDto diagnostics = new KubernetesDiagnosticsDto();
        when(authContext.isSuperAdmin()).thenReturn(true);
        when(service.getKubernetesDiagnostics()).thenReturn(diagnostics);
        ResourceMonitorController controller = new ResourceMonitorController(service, authContext);

        var response = controller.kubernetesDiagnostics();

        assertThat(response.getData()).isSameAs(diagnostics);
        verify(service).getKubernetesDiagnostics();
    }

    @Test
    void regularAdminCannotReadKubernetesDiagnostics() {
        ResourceMonitorService service = mock(ResourceMonitorService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.isSuperAdmin()).thenReturn(false);
        ResourceMonitorController controller = new ResourceMonitorController(service, authContext);

        assertThatThrownBy(controller::kubernetesDiagnostics)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("超级管理员");
        verify(service, never()).getKubernetesDiagnostics();
    }

    @Test
    void regularAdminCannotMutateNodesOrReadAndReorderGlobalQueue() {
        ResourceMonitorService service = mock(ResourceMonitorService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.isSuperAdmin()).thenReturn(false);
        ResourceMonitorController controller = new ResourceMonitorController(service, authContext);

        assertForbidden(() -> controller.addServer(new AddServerRequest()));
        assertForbidden(() -> controller.deleteServer("10.0.0.2"));
        assertForbidden(() -> controller.updateServerEnabled(
                "10.0.0.2", new UpdateServerEnabledRequest()));
        assertForbidden(() -> controller.reorderQueue("10.0.0.2", new ReorderRequest()));
        assertForbidden(() -> controller.updatePriority("10.0.0.2", new PriorityRequest()));
        assertForbidden(() -> controller.cancelQueue("10.0.0.2", "task-1"));
        assertForbidden(controller::globalQueue);
        assertForbidden(() -> controller.reorderGlobalQueue(new ReorderRequest()));
        assertForbidden(() -> controller.cancelGlobalQueue("task-1"));

        verify(service, never()).addServer(org.mockito.ArgumentMatchers.any());
        verify(service, never()).deleteServer(org.mockito.ArgumentMatchers.anyString());
        verify(service, never()).updateServerEnabled(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(service, never()).reorderQueue(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(service, never()).updatePriority(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(service, never()).cancelQueueTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(service, never()).listGlobalQueued();
        verify(service, never()).reorderGlobalQueue(org.mockito.ArgumentMatchers.any());
        verify(service, never()).cancelGlobalQueueTask(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonSuperAdministratorKeepsAggregateCountsButCannotReadTaskMetadata() {
        ResourceMonitorService service = mock(ResourceMonitorService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isSuperAdmin()).thenReturn(false);
        ServerItem item = new ServerItem();
        item.setServerIp("10.0.0.2");
        item.setRunTask(1);
        item.setWaitTask(1);
        item.setRunningTasks(List.of(new RunningTask()));
        item.setQueuedTasks(List.of(new QueuedTask()));
        when(service.getServerDetail("10.0.0.2")).thenReturn(item);
        ResourceMonitorController controller = new ResourceMonitorController(service, authContext);

        ServerItem visible = controller.serverDetail("10.0.0.2").getData();

        assertThat(visible.getRunTask()).isEqualTo(1);
        assertThat(visible.getWaitTask()).isEqualTo(1);
        assertThat(visible.getRunningTasks()).isEmpty();
        assertThat(visible.getQueuedTasks()).isEmpty();
    }

    @Test
    void superAdministratorRetainsTaskMetadata() {
        ResourceMonitorService service = mock(ResourceMonitorService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isSuperAdmin()).thenReturn(true);
        ServerItem item = new ServerItem();
        RunningTask running = new RunningTask();
        running.setName("private-training-name");
        item.setRunningTasks(List.of(running));
        item.setQueuedTasks(List.of());
        when(service.getServerDetail("10.0.0.2")).thenReturn(item);
        ResourceMonitorController controller = new ResourceMonitorController(service, authContext);

        ServerItem visible = controller.serverDetail("10.0.0.2").getData();

        assertThat(visible.getRunningTasks()).extracting(RunningTask::getName)
                .containsExactly("private-training-name");
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("超级管理员");
    }
}
