package com.tss.platform.controller;

import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import org.springframework.beans.factory.annotation.Autowired;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.service.AssetNameConflictException;
import com.tss.platform.service.AssetNameValidationException;
import com.tss.platform.service.DatasetUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dataset/upload")
public class DatasetUploadController {
    @Autowired
    private AuditHooks auditHooks;


    private final DatasetUploadService service;

    public DatasetUploadController(DatasetUploadService service) {
        this.service = service;
    }

    @PostMapping("/init")
    public ApiResponse<DatasetUploadProgressDto> init(@RequestBody DatasetUploadInitRequest req) {
        try {
            return ApiResponse.ok(service.init(req));
        } catch (AssetNameConflictException | AssetNameValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DatasetUploadProgressDto> chunk(
            @RequestParam String uploadId,
            @RequestParam Integer partIndex,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            return ApiResponse.ok(service.saveChunk(uploadId, partIndex, file));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/progress")
    public ApiResponse<DatasetUploadProgressDto> progress(@RequestParam String uploadId) {
        try {
            return ApiResponse.ok(service.getProgress(uploadId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/complete")
    public ApiResponse<Map<String, Object>> complete(@RequestBody DatasetUploadCompleteRequest req) {
        try {
            var __auditData = service.complete(req);
            Object oid = __auditData != null ? __auditData.getOrDefault("assetId", __auditData.get("id")) : null;
            auditHooks.upload(AuditObjectType.DATASET, oid != null ? String.valueOf(oid) : null, "DATASET_UPLOAD", true, null);
            return ApiResponse.ok(__auditData);
        } catch (AssetNameConflictException | AssetNameValidationException exception) {
            auditHooks.upload(AuditObjectType.DATASET, null, "DATASET_UPLOAD", false, exception.getMessage());
            throw exception;
        } catch (IllegalArgumentException e) {
            auditHooks.upload(AuditObjectType.DATASET, null, "DATASET_UPLOAD", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping(value = "/folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> folder(
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String datasetName,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String versionLabel,
            @RequestParam String type,
            @RequestParam(required = false) String cvTaskType,
            @RequestParam(required = false) String annotationFormat,
            @RequestParam(required = false) String remark,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String changeLog,
            @RequestParam(required = false) String parentVersionId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths
    ) {
        try {
            Map<String, Object> result = service.uploadCvFolder(
                    assetId,
                    datasetName,
                    version,
                    versionLabel,
                    type,
                    cvTaskType,
                    annotationFormat,
                    remark,
                    description,
                    changeLog,
                    parentVersionId,
                    files,
                    paths
            );
            Object objectId = result.getOrDefault("assetId", result.get("id"));
            auditHooks.upload(AuditObjectType.DATASET,
                    objectId != null ? String.valueOf(objectId) : datasetName,
                    "DATASET_FOLDER_UPLOAD", true, null);
            return ApiResponse.ok(result);
        } catch (AssetNameConflictException | AssetNameValidationException exception) {
            auditHooks.upload(AuditObjectType.DATASET, datasetName,
                    "DATASET_FOLDER_UPLOAD", false, exception.getMessage());
            throw exception;
        } catch (IllegalArgumentException e) {
            auditHooks.upload(AuditObjectType.DATASET, datasetName,
                    "DATASET_FOLDER_UPLOAD", false, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (RuntimeException e) {
            auditHooks.upload(AuditObjectType.DATASET, datasetName,
                    "DATASET_FOLDER_UPLOAD", false, e.getMessage());
            throw e;
        }
    }
}
