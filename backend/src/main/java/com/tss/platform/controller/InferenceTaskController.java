package com.tss.platform.controller;

import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import org.springframework.beans.factory.annotation.Autowired;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.CreateInferenceTaskRequest;
import com.tss.platform.dto.InferenceTaskDto;
import com.tss.platform.dto.InferenceTaskResultDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.InferenceTaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/inference/tasks")
public class InferenceTaskController {
    @Autowired
    private AuditHooks auditHooks;


    private final InferenceTaskService taskService;

    public InferenceTaskController(InferenceTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<InferenceTaskDto> create(@RequestBody CreateInferenceTaskRequest req) {
        try {
            var __auditData = taskService.createTask(req);
            String oid = __auditData != null && __auditData.getId() != null ? String.valueOf(__auditData.getId()) : null;
            auditHooks.inference(oid, "INFERENCE_CREATE", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException e) {
            auditHooks.inference(null, "INFERENCE_CREATE", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.inference(null, "INFERENCE_CREATE", false, e.getMessage());
            throw e;
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<InferenceTaskDto>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status
    ) {
        try {
            return ApiResponse.ok(taskService.listTasks(page, pageSize, status));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<InferenceTaskDto> detail(@PathVariable String id) {
        try {
            return ApiResponse.ok(taskService.getTask(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<InferenceTaskDto> stop(@PathVariable String id) {
        try {
            var __auditData = taskService.stopTask(id);
            auditHooks.inference(id, "INFERENCE_STOP", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException e) {
            auditHooks.inference(id, "INFERENCE_STOP", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.inference(id, "INFERENCE_STOP", false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<InferenceTaskDto> retry(@PathVariable String id) {
        try {
            var __auditData = taskService.retryTask(id);
            auditHooks.inference(id, "INFERENCE_RETRY", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException e) {
            auditHooks.inference(id, "INFERENCE_RETRY", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.inference(id, "INFERENCE_RETRY", false, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/{id}/result")
    public ApiResponse<InferenceTaskResultDto> result(@PathVariable String id) {
        try {
            return ApiResponse.ok(taskService.getResult(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        try {
            Map<String, Object> result = taskService.deleteTask(id);
            auditHooks.delete(AuditObjectType.INFERENCE_TASK, id, "INFERENCE_TASK_DELETE", true, null);
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException e) {
            auditHooks.delete(AuditObjectType.INFERENCE_TASK, id, "INFERENCE_TASK_DELETE", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.delete(AuditObjectType.INFERENCE_TASK, id, "INFERENCE_TASK_DELETE", false, e.getMessage());
            throw e;
        }
    }
}
