package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.InferenceResourceProfileDto;
import com.tss.platform.inference.InferenceResourceProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inference/resource-profiles")
public class InferenceResourceProfileController {

    private final InferenceResourceProfileService resourceProfileService;

    public InferenceResourceProfileController(InferenceResourceProfileService resourceProfileService) {
        this.resourceProfileService = resourceProfileService;
    }

    @GetMapping
    public ApiResponse<List<InferenceResourceProfileDto>> list() {
        return ApiResponse.ok(resourceProfileService.listEnabledProfiles());
    }
}
