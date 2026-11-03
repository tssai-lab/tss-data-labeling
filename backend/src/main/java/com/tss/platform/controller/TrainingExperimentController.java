package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.CreateExperimentVersionRequest;
import com.tss.platform.dto.CreateTrainingExperimentRequest;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import com.tss.platform.dto.UpdateHyperParamsRequest;
import com.tss.platform.dto.UpdateTrainingResultRequest;
import com.tss.platform.service.TrainingExperimentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/experiments")
public class TrainingExperimentController {

    private final TrainingExperimentService service;

    public TrainingExperimentController(TrainingExperimentService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<TrainingExperimentVersionDto> create(@RequestBody CreateTrainingExperimentRequest req) {
        try {
            return ApiResponse.ok(service.createExperiment(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/{experimentId}/versions")
    public ApiResponse<List<TrainingExperimentVersionDto>> history(@PathVariable String experimentId) {
        return ApiResponse.ok(service.listVersions(experimentId));
    }

    @GetMapping("/{experimentId}/versions/{versionNo}")
    public ApiResponse<TrainingExperimentVersionDto> getVersion(
            @PathVariable String experimentId,
            @PathVariable Integer versionNo
    ) {
        try {
            return ApiResponse.ok(service.getVersion(experimentId, versionNo));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/{experimentId}/versions")
    public ApiResponse<TrainingExperimentVersionDto> createVersion(
            @PathVariable String experimentId,
            @RequestBody CreateExperimentVersionRequest req
    ) {
        try {
            return ApiResponse.ok(service.createVersion(experimentId, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PutMapping("/{experimentId}/versions/{versionNo}/hyper-parameters")
    public ApiResponse<TrainingExperimentVersionDto> updateHyperParams(
            @PathVariable String experimentId,
            @PathVariable Integer versionNo,
            @RequestBody UpdateHyperParamsRequest req
    ) {
        try {
            return ApiResponse.ok(service.updateHyperParams(experimentId, versionNo, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PutMapping("/{experimentId}/versions/{versionNo}/result")
    public ApiResponse<TrainingExperimentVersionDto> updateResult(
            @PathVariable String experimentId,
            @PathVariable Integer versionNo,
            @RequestBody UpdateTrainingResultRequest req
    ) {
        try {
            return ApiResponse.ok(service.updateResult(experimentId, versionNo, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
