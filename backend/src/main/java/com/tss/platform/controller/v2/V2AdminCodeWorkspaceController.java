package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileDeleteRequest;
import com.tss.platform.dto.v2.V2CodeFileMetadata;
import com.tss.platform.dto.v2.V2CodeFileMoveRequest;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.dto.v2.V2CodeFileUpsertRequest;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceAbandonRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeWorkspacePublishRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceRevisionDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceValidateRequest;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.service.CodeArtifactStorageException;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeValidationResult;
import com.tss.platform.service.CodeValidationService;
import com.tss.platform.service.CodeWorkspaceContent;
import com.tss.platform.service.CodeWorkspaceDownload;
import com.tss.platform.service.CodeWorkspaceFileMetadata;
import com.tss.platform.service.CodeWorkspaceOverlayService;
import com.tss.platform.service.CodeWorkspacePublishService;
import com.tss.platform.service.V2CodeAssetService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/code-workspaces")
public class V2AdminCodeWorkspaceController {

    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final V2CodeAssetService assetService;
    private final CodeWorkspaceOverlayService overlayService;
    private final CodeValidationService validationService;
    private final CodeWorkspacePublishService publishService;

    public V2AdminCodeWorkspaceController(
            V2CodeAssetService assetService,
            CodeWorkspaceOverlayService overlayService,
            CodeValidationService validationService,
            CodeWorkspacePublishService publishService
    ) {
        this.assetService = assetService;
        this.overlayService = overlayService;
        this.validationService = validationService;
        this.publishService = publishService;
    }

    @GetMapping("/{workspaceId}")
    public V2CodeWorkspaceDto get(@PathVariable String workspaceId) {
        return assetService.requireAdminWorkspace(workspaceId);
    }

    @GetMapping("/{workspaceId}/tree")
    public List<V2CodeFileNode> tree(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "") String prefix
    ) {
        boolean readOnly = assetService.requireAdminWorkspace(workspaceId).readOnly();
        return overlayService.tree(workspaceId, prefix).stream()
                .map(node -> new V2CodeFileNode(
                        node.path(),
                        node.name(),
                        node.nodeType(),
                        node.extension(),
                        node.languageId(),
                        node.contentType(),
                        node.sizeBytes(),
                        node.previewable(),
                        readOnly ? false : node.editable(),
                        node.downloadable(),
                        node.reasonCode()
                ))
                .toList();
    }

    @GetMapping("/{workspaceId}/files/content")
    public V2CodeFileContent content(
            @PathVariable String workspaceId,
            @RequestParam String path
    ) {
        V2CodeWorkspaceDto workspace = assetService.requireAdminWorkspace(workspaceId);
        return V2CodeWorkspaceController.toContent(
                overlayService.content(workspaceId, path),
                workspace.readOnly()
        );
    }

    @GetMapping("/{workspaceId}/files/metadata")
    public V2CodeFileMetadata metadata(
            @PathVariable String workspaceId,
            @RequestParam String path
    ) {
        V2CodeWorkspaceDto workspace = assetService.requireAdminWorkspace(workspaceId);
        CodeWorkspaceFileMetadata metadata = overlayService.metadata(workspaceId, path);
        return V2CodeFileMetadata.from(metadata, workspace.readOnly());
    }

    @GetMapping("/{workspaceId}/files/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String workspaceId,
            @RequestParam String path
    ) {
        assetService.requireAdminWorkspace(workspaceId);
        CodeWorkspaceDownload download = overlayService.download(workspaceId, path);
        byte[] bytes = download.bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.descriptor().contentType()))
                .contentLength(bytes.length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        V2CodeVersionController.contentDisposition(
                                download.descriptor().name()
                        )
                )
                .header(NOSNIFF_HEADER, "nosniff")
                .body(bytes);
    }

    @PutMapping("/{workspaceId}/files")
    public V2CodeFileContent upsert(
            @PathVariable String workspaceId,
            @RequestParam String path,
            @RequestBody(required = false) V2CodeFileUpsertRequest request
    ) {
        V2CodeWorkspaceDto workspace = assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        if (request.content() == null) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "请求参数不正确，请检查后重试",
                    java.util.Map.of("reasonCode", "CONTENT_REQUIRED")
            );
        }
        CodeWorkspaceContent result = overlayService.upsert(
                workspaceId,
                path,
                request.content().getBytes(StandardCharsets.UTF_8),
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                ),
                request.expectedContentHash()
        );
        return V2CodeWorkspaceController.toContent(result, workspace.readOnly());
    }

    @PostMapping("/{workspaceId}/files/move")
    public V2CodeFileContent move(
            @PathVariable String workspaceId,
            @RequestBody(required = false) V2CodeFileMoveRequest request
    ) {
        V2CodeWorkspaceDto workspace = assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        CodeWorkspaceContent result = overlayService.move(
                workspaceId,
                request.sourcePath(),
                request.targetPath(),
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                ),
                request.expectedContentHash()
        );
        return V2CodeWorkspaceController.toContent(result, workspace.readOnly());
    }

    @DeleteMapping("/{workspaceId}/files")
    public V2CodeWorkspaceRevisionDto delete(
            @PathVariable String workspaceId,
            @RequestParam String path,
            @RequestBody(required = false) V2CodeFileDeleteRequest request
    ) {
        assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        long revision = overlayService.delete(
                workspaceId,
                path,
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                ),
                request.expectedContentHash()
        );
        return new V2CodeWorkspaceRevisionDto(workspaceId, revision);
    }

    @PostMapping("/{workspaceId}/validate")
    public V2CodeValidationResult validate(
            @PathVariable String workspaceId,
            @RequestBody(required = false) V2CodeWorkspaceValidateRequest request
    ) {
        assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        CodeValidationResult result = validationService.validateWorkspace(
                workspaceId,
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                )
        );
        if (!result.passed()) {
            if ("STORAGE_READ_FAILED".equals(result.reasonCode())) {
                throw new CodeArtifactStorageException();
            }
            throw new CodeValidationException(
                    V2CodeWorkspaceController.safeReasonCode(result.reasonCode()),
                    "Code workspace validation failed"
            );
        }
        return V2CodeValidationResult.from(result);
    }

    @PostMapping("/{workspaceId}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public V2CodeVersionDto publish(
            @PathVariable String workspaceId,
            @RequestBody(required = false) V2CodeWorkspacePublishRequest request
    ) {
        assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        CodeVersion version = publishService.publish(
                workspaceId,
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                ),
                request.version()
        );
        return V2CodeVersionDto.from(version);
    }

    @PostMapping("/{workspaceId}/abandon")
    public V2CodeWorkspaceDto abandon(
            @PathVariable String workspaceId,
            @RequestBody(required = false) V2CodeWorkspaceAbandonRequest request
    ) {
        assetService.requireAdminWorkspace(workspaceId);
        V2CodeWorkspaceController.requireRequest(request);
        return assetService.abandonWorkspaceAdmin(
                workspaceId,
                V2CodeWorkspaceController.requiredRevision(
                        request.expectedWorkspaceRevision()
                )
        );
    }
}
