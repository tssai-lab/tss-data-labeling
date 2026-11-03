package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.dto.v2.V2CodeVersionArchiveRequest;
import com.tss.platform.dto.v2.V2CodeVersionDeprecateRequest;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.dto.v2.V2CodeVersionValidateRequest;
import com.tss.platform.service.V2CodeVersionQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/admin")
public class V2AdminCodeVersionController {

    private final V2CodeVersionQueryService service;

    public V2AdminCodeVersionController(V2CodeVersionQueryService service) {
        this.service = service;
    }

    @GetMapping("/code-assets/{assetId}/versions")
    public List<V2CodeVersionDto> listForAsset(@PathVariable String assetId) {
        return service.listForAssetAdmin(assetId);
    }

    @GetMapping("/code-versions/{versionId}")
    public V2CodeVersionDto get(@PathVariable String versionId) {
        return service.getAdmin(versionId);
    }

    @GetMapping("/code-versions/{versionId}/tree")
    public List<V2CodeFileNode> tree(
            @PathVariable String versionId,
            @RequestParam(required = false) String prefix
    ) {
        return service.treeAdmin(versionId, prefix);
    }

    @GetMapping("/code-versions/{versionId}/files/content")
    public V2CodeFileContent content(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return service.contentAdmin(versionId, path);
    }

    @GetMapping("/code-versions/{versionId}/files/download")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return V2CodeVersionController.download(
                service.downloadFileAdmin(versionId, path)
        );
    }

    @GetMapping("/code-versions/{versionId}/download")
    public ResponseEntity<byte[]> downloadArchive(@PathVariable String versionId) {
        return V2CodeVersionController.download(
                service.downloadArchiveAdmin(versionId)
        );
    }

    @PostMapping("/code-versions/{versionId}/validate")
    public V2CodeValidationResult validate(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionValidateRequest ignored
    ) {
        return service.validateAdmin(versionId);
    }

    @PostMapping("/code-versions/{versionId}/deprecate")
    public V2CodeVersionDto deprecate(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionDeprecateRequest ignored
    ) {
        return service.deprecateAdmin(versionId);
    }

    @PostMapping("/code-versions/{versionId}/archive")
    public V2CodeVersionDto archive(
            @PathVariable String versionId,
            @RequestBody(required = false) V2CodeVersionArchiveRequest ignored
    ) {
        return service.archiveAdmin(versionId);
    }
}
