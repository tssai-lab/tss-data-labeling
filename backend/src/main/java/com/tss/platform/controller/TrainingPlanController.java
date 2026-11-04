package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.resource.TrainingResourceCapabilityDto;
import com.tss.platform.dto.resource.TrainingHardwareOptionDto;
import com.tss.platform.service.TrainingHardwareOptionService;
import com.tss.platform.service.TrainingResourceCapabilityService;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only catalogue used by the task-creation UI; plans are no longer hard-coded there. */
@RestController
@RequestMapping("/api/training-plans")
public class TrainingPlanController {

    private final TrainingPlanRegistry trainingPlanRegistry;
    private final TrainingResourceCapabilityService resourceCapabilityService;
    private final TrainingHardwareOptionService hardwareOptionService;

    public TrainingPlanController(
            TrainingPlanRegistry trainingPlanRegistry,
            TrainingResourceCapabilityService resourceCapabilityService,
            TrainingHardwareOptionService hardwareOptionService
    ) {
        this.trainingPlanRegistry = trainingPlanRegistry;
        this.resourceCapabilityService = resourceCapabilityService;
        this.hardwareOptionService = hardwareOptionService;
    }

    @GetMapping
    public ApiResponse<List<TrainingPlanDefinition>> list(
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled
    ) {
        return ApiResponse.ok(trainingPlanRegistry.listLatest(includeDisabled));
    }

    @GetMapping("/{planId}/resource-capabilities")
    public ApiResponse<TrainingResourceCapabilityDto> resourceCapabilities(
            @PathVariable String planId,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam("resourceProfileId") String resourceProfileId
    ) {
        return ApiResponse.ok(resourceCapabilityService.capability(planId, version, resourceProfileId));
    }

    @GetMapping("/{planId}/hardware-options")
    public ApiResponse<List<TrainingHardwareOptionDto>> hardwareOptions(
            @PathVariable String planId,
            @RequestParam(value = "version", required = false) String version
    ) {
        return ApiResponse.ok(hardwareOptionService.options(planId, version));
    }
}
