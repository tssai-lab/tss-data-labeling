package com.tss.platform.controller;

import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import org.springframework.beans.factory.annotation.Autowired;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.CreateTrainingExperimentRequest;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import com.tss.platform.dto.UpdateTrainingResultRequest;
import com.tss.platform.service.TrainingExperimentService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/task")
public class TrainingTaskController {
    @Autowired
    private AuditHooks auditHooks;


    private final TrainingExperimentService service;

    public TrainingTaskController(TrainingExperimentService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ApiResponse<TrainingExperimentVersionDto> create(@RequestBody CreateTrainingExperimentRequest req) {
        try {
            var __auditData = service.createExperiment(req);
            String oid = __auditData != null && __auditData.getId() != null ? String.valueOf(__auditData.getId()) : null;
            auditHooks.train(oid, "TRAIN_CREATE", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException e) {
            auditHooks.train(null, "TRAIN_CREATE", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.train(null, "TRAIN_CREATE", false, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String experimentId) {
        var page = service.listLatestExperiments(current, pageSize, status, name, experimentId);
        Map<String, Object> result = new HashMap<>();
        result.put("data", page.getContent());
        result.put("total", page.getTotalElements());
        return ApiResponse.ok(result);
    }

    @GetMapping("/detail")
    public ApiResponse<TrainingExperimentVersionDto> detail(@RequestParam String id) {
        try {
            return ApiResponse.ok(service.getByIdOrExperimentId(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/stop")
    public ApiResponse<TrainingExperimentVersionDto> stop(@RequestParam String id) {
        try {
            var __auditData = service.stopTraining(id);
            auditHooks.train(id, "TRAIN_STOP", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException e) {
            auditHooks.train(id, "TRAIN_STOP", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.train(id, "TRAIN_STOP", false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/publish-model")
    public ApiResponse<TrainingExperimentVersionDto> publishModel(@PathVariable String id) {
        try {
            return ApiResponse.ok(service.requestModelPublish(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/result")
    public ApiResponse<TrainingExperimentVersionDto> updateResult(
            @RequestParam String id,
            @RequestBody UpdateTrainingResultRequest req
    ) {
        try {
            return ApiResponse.ok(service.updateResultByIdOrExperimentId(id, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/delete")
    public ApiResponse<Object> delete(@RequestParam String id) {
        try {
            service.deleteExperiment(id);
            auditHooks.delete(AuditObjectType.TRAIN_TASK, id, "TRAIN_TASK_DELETE", true, null);
            return ApiResponse.ok(null);
        } catch (IllegalArgumentException e) {
            auditHooks.delete(AuditObjectType.TRAIN_TASK, id, "TRAIN_TASK_DELETE", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.delete(AuditObjectType.TRAIN_TASK, id, "TRAIN_TASK_DELETE", false, e.getMessage());
            throw e;
        }
    }
}
