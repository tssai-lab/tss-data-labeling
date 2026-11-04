package com.tss.platform.controller;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.InferenceTaskDto;
import com.tss.platform.dto.UpdateInferenceResultRequest;
import com.tss.platform.service.InferenceTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/inference")
public class InternalInferenceCallbackController {

    private final TrainingKubernetesProperties properties;
    private final InferenceTaskService taskService;

    public InternalInferenceCallbackController(
            TrainingKubernetesProperties properties,
            InferenceTaskService taskService
    ) {
        this.properties = properties;
        this.taskService = taskService;
    }

    @PostMapping("/result")
    public ApiResponse<InferenceTaskDto> updateResult(
            @RequestParam String id,
            @RequestParam(required = false) Integer attempt,
            @RequestBody UpdateInferenceResultRequest req,
            HttpServletRequest request
    ) {
        String token = request.getHeader("X-Internal-Token");
        if (!properties.matchesInternalCallbackToken(token)) {
            return ApiResponse.fail("无效的内部回调 token");
        }
        try {
            return ApiResponse.ok(taskService.updateResultInternal(id, attempt, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
