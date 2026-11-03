package com.tss.platform.controller;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.security.AuthSessionStoreHealth;
import com.tss.platform.service.MinioService;
import com.tss.platform.training.TrainingEnvironmentService;
import com.tss.platform.training.TrainingEnvironmentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class SystemHealthController {

    private final JdbcTemplate jdbcTemplate;
    private final MinioService minioService;
    private final TrainingEnvironmentService trainingEnvironmentService;
    private final TrainingKubernetesProperties trainingProperties;
    private final AuthSessionStoreHealth authSessionStoreHealth;
    private final String applicationName;
    private final String nodeId;

    public SystemHealthController(
            JdbcTemplate jdbcTemplate,
            MinioService minioService,
            TrainingEnvironmentService trainingEnvironmentService,
            TrainingKubernetesProperties trainingProperties,
            AuthSessionStoreHealth authSessionStoreHealth,
            @Value("${spring.application.name:application}") String applicationName,
            @Value("${TSS_NODE_ID:unknown}") String nodeId
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.minioService = minioService;
        this.trainingEnvironmentService = trainingEnvironmentService;
        this.trainingProperties = trainingProperties;
        this.authSessionStoreHealth = authSessionStoreHealth;
        this.applicationName = applicationName;
        this.nodeId = nodeId;
    }

    @GetMapping("/live")
    public Map<String, Object> live() {
        Map<String, Object> body = baseBody("UP");
        body.put("check", "liveness");
        return body;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean ready = true;

        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            boolean databaseReady = Integer.valueOf(1).equals(value);
            components.put("database", databaseReady ? "UP" : "DOWN");
            ready &= databaseReady;
        } catch (Exception ignored) {
            components.put("database", "DOWN");
            ready = false;
        }

        try {
            minioService.assertConnected();
            components.put("objectStorage", "UP");
        } catch (Exception ignored) {
            components.put("objectStorage", "DOWN");
            ready = false;
        }

        try {
            TrainingEnvironmentStatus trainingStatus = trainingEnvironmentService.getStatus();
            boolean trainingReady = isTrainingReady(trainingStatus);
            components.put("training", trainingComponentStatus(trainingStatus, trainingReady));
            ready &= trainingReady;
        } catch (Exception ignored) {
            components.put("training", "DOWN");
            ready = false;
        }

        String authSessionStatus = authSessionStoreHealth.readinessStatus();
        components.put("authSession", authSessionStatus);
        ready &= !"DOWN".equals(authSessionStatus);

        Map<String, Object> body = baseBody(ready ? "UP" : "DOWN");
        body.put("check", "readiness");
        body.put("components", components);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isTrainingReady(TrainingEnvironmentStatus status) {
        if (!trainingProperties.isEnabled()) {
            return true;
        }
        if (status.getState() == TrainingEnvironmentStatus.State.READY) {
            return true;
        }
        return trainingProperties.isFallbackToLocal()
                && status.getState() == TrainingEnvironmentStatus.State.DEGRADED;
    }

    private String trainingComponentStatus(TrainingEnvironmentStatus status, boolean ready) {
        if (!trainingProperties.isEnabled()) {
            return "DISABLED";
        }
        if (ready && status.getState() == TrainingEnvironmentStatus.State.DEGRADED) {
            return "UP_WITH_FALLBACK";
        }
        return ready ? "UP" : "DOWN";
    }

    private Map<String, Object> baseBody(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("application", applicationName);
        body.put("nodeId", nodeId);
        body.put("timestamp", Instant.now());
        return body;
    }
}
