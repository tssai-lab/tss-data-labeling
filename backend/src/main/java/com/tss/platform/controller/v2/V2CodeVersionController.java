package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2CodeApprovalRequest;
import com.tss.platform.dto.v2.V2CodeApprovalResult;
import com.tss.platform.dto.v2.V2CodeArtifactUpgradeResult;
import com.tss.platform.dto.v2.V2CodeConsumerManifest;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.dto.v2.V2CodeRiskAssessmentDetail;
import com.tss.platform.dto.v2.V2CodeVersionArchiveRequest;
import com.tss.platform.dto.v2.V2CodeVersionDeprecateRequest;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.dto.v2.V2CodeVersionValidateRequest;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeArtifactUpgradeService;
import com.tss.platform.service.V2CodeVersionQueryService;
import com.tss.platform.service.V2CodeRiskQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2")
public class V2CodeVersionController {

    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final V2CodeVersionQueryService service;
    private final CodeArtifactUpgradeService artifactUpgradeService;
    private final V2CodeRiskQueryService riskQueryService;

    public V2CodeVersionController(
            V2CodeVersionQueryService service,
            CodeArtifactUpgradeService artifactUpgradeService,
            V2CodeRiskQueryService riskQueryService
    ) {
        this.service = service;
        this.artifactUpgradeService = artifactUpgradeService;
        this.riskQueryService = riskQueryService;
    }

    @GetMapping("/code-assets/{assetId}/versions")
    public List<V2CodeVersionDto> listForAsset(@PathVariable String assetId) {
        return service.listForAsset(assetId);
    }

    @GetMapping("/code-versions/{versionId}")
    public V2CodeVersionDto get(@PathVariable String versionId) {
        return service.get(versionId);
    }

    @GetMapping("/code-versions/{versionId}/tree")
    public List<V2CodeFileNode> tree(
            @PathVariable String versionId,
            @RequestParam(required = false) String prefix
    ) {
        return service.tree(versionId, prefix);
    }

    @GetMapping("/code-versions/{versionId}/files/content")
    public V2CodeFileContent content(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return service.content(versionId, path);
    }

    @GetMapping("/code-versions/{versionId}/files/download")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return download(service.downloadFile(versionId, path));
    }

    @GetMapping("/code-versions/{versionId}/download")
    public ResponseEntity<byte[]> downloadArchive(@PathVariable String versionId) {
        return download(service.downloadArchive(versionId));
    }

    @GetMapping("/code-versions/{versionId}/consumer-manifest")
    public V2CodeConsumerManifest consumerManifest(@PathVariable String versionId) {
        return service.consumerManifest(versionId);
    }

    @PostMapping("/code-versions/{versionId}/validate")
    public V2CodeValidationResult validate(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionValidateRequest ignored
    ) {
        return service.validate(versionId);
    }

    @GetMapping("/code-versions/{versionId}/risk-assessment")
    public V2CodeRiskAssessmentDetail riskAssessment(@PathVariable String versionId) {
        return riskQueryService.get(versionId);
    }

    @PostMapping("/code-versions/{versionId}/approval")
    public V2CodeApprovalResult approve(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeApprovalRequest request
    ) {
        return service.approve(versionId, request);
    }

    @PostMapping("/code-versions/{versionId}/artifact-upgrade")
    public V2CodeArtifactUpgradeResult upgradeArtifact(@PathVariable String versionId) {
        return artifactUpgradeService.upgrade(versionId);
    }

    @PostMapping("/code-versions/{versionId}/deprecate")
    public V2CodeVersionDto deprecate(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionDeprecateRequest ignored
    ) {
        return service.deprecate(versionId);
    }

    @PostMapping("/code-versions/{versionId}/archive")
    public V2CodeVersionDto archive(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionArchiveRequest ignored
    ) {
        return service.archive(versionId);
    }

    static ResponseEntity<byte[]> download(
            V2CodeVersionQueryService.Download download
    ) {
        byte[] bytes = download.bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(download.fileName()))
                .header(NOSNIFF_HEADER, "nosniff")
                .body(bytes);
    }

    static String contentDisposition(String fileName) {
        if (fileName == null || fileName.isBlank() || containsControl(fileName)) {
            throw new CodeValidationException(
                    "INVALID_DOWNLOAD_FILENAME",
                    "Download file name is invalid"
            );
        }
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
        return "attachment; filename=\"download\"; filename*=UTF-8''" + encoded;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
