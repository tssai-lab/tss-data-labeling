package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.InferenceScriptUploadResultDto;
import com.tss.platform.dto.InferenceScriptVersionDto;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.InferenceScriptService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inference/scripts")
public class InferenceScriptController {

    private final InferenceScriptService scriptService;
    private final AuditHooks auditHooks;

    public InferenceScriptController(InferenceScriptService scriptService, AuditHooks auditHooks) {
        this.scriptService = scriptService;
        this.auditHooks = auditHooks;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InferenceScriptUploadResultDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String scriptName,
            @RequestParam(defaultValue = "v1") String version,
            @RequestParam(defaultValue = "PYTHON3") String runtime,
            @RequestParam String entryFile,
            @RequestParam(required = false) String paramsSchemaJson,
            @RequestParam(required = false) String remark
    ) {
        try {
            InferenceScriptUploadResultDto result = scriptService.upload(
                    file,
                    scriptName,
                    version,
                    runtime,
                    entryFile,
                    paramsSchemaJson,
                    remark
            );
            auditHooks.upload(AuditObjectType.INFERENCE_SCRIPT, result.getScriptAssetId(),
                    "INFERENCE_SCRIPT_UPLOAD", true, null);
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException e) {
            auditHooks.upload(AuditObjectType.INFERENCE_SCRIPT, scriptName,
                    "INFERENCE_SCRIPT_UPLOAD", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.upload(AuditObjectType.INFERENCE_SCRIPT, scriptName,
                    "INFERENCE_SCRIPT_UPLOAD", false, e.getMessage());
            throw e;
        }
    }

    @GetMapping
    public ApiResponse<List<InferenceScriptVersionDto>> list() {
        return ApiResponse.ok(scriptService.listScripts());
    }

    @GetMapping("/{versionId}")
    public ApiResponse<InferenceScriptVersionDto> detail(@PathVariable String versionId) {
        try {
            return ApiResponse.ok(scriptService.getScript(versionId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{versionId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String versionId) {
        try {
            Map<String, Object> result = scriptService.deleteScriptVersion(versionId);
            auditHooks.delete(AuditObjectType.INFERENCE_SCRIPT, versionId,
                    "INFERENCE_SCRIPT_DELETE", true, null);
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException e) {
            auditHooks.delete(AuditObjectType.INFERENCE_SCRIPT, versionId,
                    "INFERENCE_SCRIPT_DELETE", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.delete(AuditObjectType.INFERENCE_SCRIPT, versionId,
                    "INFERENCE_SCRIPT_DELETE", false, e.getMessage());
            throw e;
        }
    }
}
