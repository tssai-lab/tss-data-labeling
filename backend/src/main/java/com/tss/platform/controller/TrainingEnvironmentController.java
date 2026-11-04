package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.TrainingEnvironmentService;
import com.tss.platform.training.TrainingEnvironmentStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/training/environment")
public class TrainingEnvironmentController {

    private final TrainingEnvironmentService environmentService;
    private final AuthContext authContext;

    public TrainingEnvironmentController(
            TrainingEnvironmentService environmentService,
            AuthContext authContext
    ) {
        this.environmentService = environmentService;
        this.authContext = authContext;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        TrainingEnvironmentStatus status = environmentService.getStatus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", status.getState() != null ? status.getState().name() : "UNKNOWN");
        body.put("kubernetesEnabled", status.isKubernetesEnabled());
        body.put("kubernetesReady", status.isKubernetesReady());
        body.put("fallbackToLocal", status.isFallbackToLocal());
        body.put("message", status.getMessage());
        body.put("checkedAt", status.getCheckedAt());
        if (authContext.isSuperAdmin()) {
            body.put("clusterName", status.getClusterName());
            body.put("namespace", status.getNamespace());
            body.put("workerImage", status.getWorkerImage());
            body.put("kubeconfig", status.getKubeconfig());
            body.put("lastError", status.getLastError());
        }
        return ApiResponse.ok(body);
    }
}
