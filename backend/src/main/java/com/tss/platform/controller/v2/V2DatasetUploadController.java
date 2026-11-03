package com.tss.platform.controller.v2;

import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.v2.V2DatasetUploadDto;
import com.tss.platform.dto.v2.V2DatasetUploadCompleteRequest;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.V2DatasetUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/dataset-uploads")
public class V2DatasetUploadController {

    private final V2DatasetUploadService service;
    private final AuditHooks auditHooks;

    public V2DatasetUploadController(V2DatasetUploadService service, AuditHooks auditHooks) {
        this.service = service;
        this.auditHooks = auditHooks;
    }

    @PostMapping("/init")
    public V2DatasetUploadDto init(@RequestBody DatasetUploadInitRequest request) {
        return service.init(request);
    }

    @PostMapping(
            value = "/{uploadId}/chunks",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public V2DatasetUploadDto uploadChunk(
            @PathVariable String uploadId,
            @RequestParam Integer partIndex,
            @RequestParam("file") MultipartFile file
    ) {
        return service.saveChunk(uploadId, partIndex, file);
    }

    @GetMapping("/{uploadId}")
    public V2DatasetUploadDto get(@PathVariable String uploadId) {
        return service.get(uploadId);
    }

    @PostMapping("/{uploadId}/complete")
    public V2DatasetUploadDto complete(
            @PathVariable String uploadId,
            @RequestBody(required = false)
            V2DatasetUploadCompleteRequest request
    ) {
        try {
            V2DatasetUploadDto result = service.complete(uploadId, request);
            String objectId = result.getDatasetId() != null ? result.getDatasetId() : uploadId;
            auditHooks.upload(AuditObjectType.DATASET, objectId, "DATASET_UPLOAD_V2", true, null);
            return result;
        } catch (RuntimeException exception) {
            auditHooks.upload(AuditObjectType.DATASET, uploadId, "DATASET_UPLOAD_V2", false, exception.getMessage());
            throw exception;
        }
    }

    @PostMapping("/{uploadId}/cancel")
    public V2DatasetUploadDto cancel(
            @PathVariable String uploadId,
            @RequestBody V2DatasetUploadCompleteRequest request
    ) {
        return service.cancel(uploadId, request);
    }
}
