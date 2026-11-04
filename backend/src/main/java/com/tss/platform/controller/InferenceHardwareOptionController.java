package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.InferenceHardwareOptionDto;
import com.tss.platform.inference.InferenceHardwareOptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inference/hardware-options")
public class InferenceHardwareOptionController {

    private final InferenceHardwareOptionService service;

    public InferenceHardwareOptionController(InferenceHardwareOptionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<InferenceHardwareOptionDto>> list() {
        return ApiResponse.ok(service.listOptions());
    }
}
