package com.tss.platform.controller;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import com.tss.platform.dto.UpdateTrainingResultRequest;
import com.tss.platform.service.TrainingExperimentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/training")
public class InternalTrainingCallbackController {

    private final TrainingKubernetesProperties properties;
    private final TrainingExperimentService trainingExperimentService;

    public InternalTrainingCallbackController(
            TrainingKubernetesProperties properties,
            TrainingExperimentService trainingExperimentService
    ) {
        this.properties = properties;
        this.trainingExperimentService = trainingExperimentService;
    }

    @PostMapping("/result")
    public ApiResponse<TrainingExperimentVersionDto> updateResult(
            @RequestParam String id,
            @RequestBody UpdateTrainingResultRequest req,
            HttpServletRequest request
    ) {
        String token = request.getHeader("X-Internal-Token");
        if (!properties.matchesInternalCallbackToken(token)) {
            return ApiResponse.fail("无效的内部回调 token");
        }
        try {
            return ApiResponse.ok(trainingExperimentService.updateResultInternal(id, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
