package com.tss.platform.controller;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.security.AuthSessionStoreHealth;
import com.tss.platform.service.MinioService;
import com.tss.platform.training.TrainingEnvironmentService;
import com.tss.platform.training.TrainingEnvironmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SystemHealthControllerTest {

    private JdbcTemplate jdbcTemplate;
    private MinioService minioService;
    private TrainingEnvironmentService trainingEnvironmentService;
    private TrainingKubernetesProperties trainingProperties;
    private AuthSessionStoreHealth authSessionStoreHealth;
    private SystemHealthController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        minioService = mock(MinioService.class);
        trainingEnvironmentService = mock(TrainingEnvironmentService.class);
        trainingProperties = new TrainingKubernetesProperties();
        trainingProperties.setEnabled(true);
        trainingProperties.setFallbackToLocal(false);
        authSessionStoreHealth = mock(AuthSessionStoreHealth.class);
        when(authSessionStoreHealth.readinessStatus()).thenReturn("UP");
        controller = new SystemHealthController(
                jdbcTemplate,
                minioService,
                trainingEnvironmentService,
                trainingProperties,
                authSessionStoreHealth,
                "test-application",
                "test-node"
        );
    }

    @Test
    void livenessDoesNotDependOnExternalServices() {
        Map<String, Object> body = controller.live();

        assertEquals("UP", body.get("status"));
        assertEquals("liveness", body.get("check"));
        assertEquals("test-node", body.get("nodeId"));
        verifyNoInteractions(jdbcTemplate, minioService, trainingEnvironmentService, authSessionStoreHealth);
    }

    @Test
    void readinessIsUpWhenAllRequiredComponentsAreReady() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(trainingEnvironmentService.getStatus()).thenReturn(trainingStatus(
                TrainingEnvironmentStatus.State.READY
        ));

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertEquals("UP", components.get("database"));
        assertEquals("UP", components.get("objectStorage"));
        assertEquals("UP", components.get("training"));
        assertEquals("UP", components.get("authSession"));
    }

    @Test
    void readinessIsUnavailableWhenObjectStorageIsDown() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(trainingEnvironmentService.getStatus()).thenReturn(trainingStatus(
                TrainingEnvironmentStatus.State.READY
        ));
        doThrow(new IllegalStateException("unavailable")).when(minioService).assertConnected();

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
    }

    @Test
    void readinessAcceptsLocalFallbackWhenConfigured() throws Exception {
        trainingProperties.setFallbackToLocal(true);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(trainingEnvironmentService.getStatus()).thenReturn(trainingStatus(
                TrainingEnvironmentStatus.State.DEGRADED
        ));

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertEquals("UP_WITH_FALLBACK", components.get("training"));
    }

    @Test
    void readinessIsUnavailableWhenTrainingStatusCannotBeRead() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(trainingEnvironmentService.getStatus()).thenThrow(new IllegalStateException("unavailable"));

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertEquals("DOWN", components.get("training"));
    }

    @Test
    void readinessIsUnavailableWhenRedisSessionStoreIsDown() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(trainingEnvironmentService.getStatus()).thenReturn(trainingStatus(
                TrainingEnvironmentStatus.State.READY
        ));
        when(authSessionStoreHealth.readinessStatus()).thenReturn("DOWN");

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertEquals("DOWN", components.get("authSession"));
    }

    private TrainingEnvironmentStatus trainingStatus(TrainingEnvironmentStatus.State state) {
        TrainingEnvironmentStatus status = new TrainingEnvironmentStatus();
        status.setState(state);
        status.setKubernetesEnabled(true);
        status.setKubernetesReady(state == TrainingEnvironmentStatus.State.READY);
        return status;
    }
}
