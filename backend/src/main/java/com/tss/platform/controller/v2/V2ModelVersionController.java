package com.tss.platform.controller.v2;

import com.tss.platform.dto.ModelCodePreviewDto;
import com.tss.platform.dto.ModelCurrentVersionRequest;
import com.tss.platform.dto.v2.V2ModelConsumerManifest;
import com.tss.platform.dto.v2.V2ModelCurrentVersionResult;
import com.tss.platform.dto.v2.V2ModelFileNode;
import com.tss.platform.service.V2ModelVersionService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v2")
public class V2ModelVersionController {

    private final V2ModelVersionService service;

    public V2ModelVersionController(V2ModelVersionService service) {
        this.service = service;
    }

    @PutMapping("/model-assets/{assetId}/current-version")
    public V2ModelCurrentVersionResult switchCurrent(
            @PathVariable String assetId,
            @RequestBody ModelCurrentVersionRequest request
    ) {
        return service.switchCurrent(
                assetId,
                request == null ? null : request.getVersionId()
        );
    }

    @GetMapping("/model-versions/{versionId}/consumer-manifest")
    public V2ModelConsumerManifest consumerManifest(@PathVariable String versionId) {
        return service.consumerManifest(versionId);
    }

    @GetMapping("/model-versions/{versionId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String versionId) {
        V2ModelVersionService.Download download = service.download(versionId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff");
        if (download.sha256() != null) {
            response.header("X-Artifact-Sha256", download.sha256());
        }
        return response.body(new InputStreamResource(download.inputStream()));
    }

    @GetMapping("/model-versions/{versionId}/files")
    public List<V2ModelFileNode> files(@PathVariable String versionId) {
        return service.files(versionId);
    }

    @GetMapping("/model-versions/{versionId}/files/content")
    public ModelCodePreviewDto content(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return service.content(versionId, path);
    }
}
