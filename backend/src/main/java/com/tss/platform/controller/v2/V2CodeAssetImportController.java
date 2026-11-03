package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2CodeAssetImportMetadata;
import com.tss.platform.dto.v2.V2CodeAssetImportResult;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.CodeAssetImportCommand;
import com.tss.platform.service.CodeAssetImportResult;
import com.tss.platform.service.CodeAssetImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/code-assets")
public class V2CodeAssetImportController {

    private final CodeAssetImportService importService;
    private final AuditHooks auditHooks;

    public V2CodeAssetImportController(CodeAssetImportService importService, AuditHooks auditHooks) {
        this.importService = importService;
        this.auditHooks = auditHooks;
    }

    @PostMapping(
            value = "/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<V2CodeAssetImportResult> importAsset(
            @Valid @RequestPart("metadata") V2CodeAssetImportMetadata metadata,
            @RequestPart("file") MultipartFile file
    ) {
        try {
            CodeAssetImportResult result = importService.importAsset(
                    file,
                    new CodeAssetImportCommand(
                            metadata.name(),
                            metadata.version(),
                            metadata.trainingProfile(),
                            metadata.purpose(),
                            metadata.runtime(),
                            metadata.entryScript(),
                            metadata.trainingType(),
                            metadata.remark()
                    )
            );
            auditHooks.upload(AuditObjectType.TRAINING_CODE, result.assetId(), "CODE_UPLOAD_V2", true, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(result));
        } catch (RuntimeException exception) {
            auditHooks.upload(AuditObjectType.TRAINING_CODE, metadata.name(), "CODE_UPLOAD_V2", false,
                    exception.getMessage());
            throw exception;
        }
    }

    private static V2CodeAssetImportResult toDto(CodeAssetImportResult result) {
        return new V2CodeAssetImportResult(
                result.assetId(),
                result.versionId(),
                result.version(),
                result.fileName(),
                result.sizeBytes(),
                result.trainingProfile(),
                result.status(),
                result.validationStatus(),
                result.validationPolicyVersion(),
                result.approvalStatus(),
                result.artifactSha256(),
                result.createdAt()
        );
    }
}
