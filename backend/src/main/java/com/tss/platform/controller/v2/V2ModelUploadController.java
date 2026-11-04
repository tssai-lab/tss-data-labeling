package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2ModelUploadDto;
import com.tss.platform.dto.v2.V2ModelUploadInitRequest;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.ModelUploadService;
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
@RequestMapping("/api/v2/model-uploads")
public class V2ModelUploadController {

    private final ModelUploadService service;
    private final AuditHooks auditHooks;

    public V2ModelUploadController(ModelUploadService service, AuditHooks auditHooks) {
        this.service = service;
        this.auditHooks = auditHooks;
    }

    @PostMapping("/init")
    public V2ModelUploadDto init(@RequestBody V2ModelUploadInitRequest request) {
        return service.initV2(request);
    }

    @PostMapping(
            value = "/{uploadId}/chunks",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public V2ModelUploadDto chunk(
            @PathVariable String uploadId,
            @RequestParam Integer partIndex,
            @RequestParam("file") MultipartFile file
    ) {
        return service.saveChunkV2(uploadId, partIndex, file);
    }

    @GetMapping("/{uploadId}")
    public V2ModelUploadDto progress(@PathVariable String uploadId) {
        return service.getProgressV2(uploadId);
    }

    @PostMapping("/{uploadId}/complete")
    public V2ModelUploadDto complete(@PathVariable String uploadId) {
        try {
            V2ModelUploadDto result = service.completeV2(uploadId);
            String objectId = result.getAssetId() != null ? result.getAssetId() : result.getModelId();
            auditHooks.upload(AuditObjectType.MODEL, objectId, "MODEL_UPLOAD_V2", true, null);
            return result;
        } catch (RuntimeException exception) {
            auditHooks.upload(AuditObjectType.MODEL, uploadId, "MODEL_UPLOAD_V2", false, exception.getMessage());
            throw exception;
        }
    }
}
