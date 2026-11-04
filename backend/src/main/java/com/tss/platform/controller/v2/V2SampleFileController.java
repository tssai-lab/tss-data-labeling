package com.tss.platform.controller.v2;

import com.tss.platform.service.SampleFileService;
import com.tss.platform.service.SampleFileService.SampleFileStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v2")
public class V2SampleFileController {

    private final SampleFileService service;

    public V2SampleFileController(SampleFileService service) {
        this.service = service;
    }

    @GetMapping("/dataset-sample-data/{dataId}/preview")
    public ResponseEntity<InputStreamResource> previewData(
            @PathVariable String dataId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        return stream(service.openDataPreview(dataId, rangeHeader), true);
    }

    @GetMapping("/dataset-sample-data/{dataId}/download")
    public ResponseEntity<InputStreamResource> downloadData(@PathVariable String dataId) {
        return stream(service.openDataDownload(dataId), false);
    }

    @GetMapping("/dataset-annotations/{annotationId}/download")
    public ResponseEntity<InputStreamResource> downloadAnnotation(
            @PathVariable String annotationId
    ) {
        return stream(service.openAnnotationDownload(annotationId), false);
    }

    @GetMapping(
            "/dataset-workspaces/{workspaceId}/samples/{sampleId}"
                    + "/data/{dataId}/preview"
    )
    public ResponseEntity<InputStreamResource> previewWorkspaceData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false)
            String rangeHeader
    ) {
        return stream(
                service.openWorkspaceDataPreview(
                        workspaceId,
                        sampleId,
                        dataId,
                        rangeHeader
                ),
                true
        );
    }

    @GetMapping(
            "/dataset-workspaces/{workspaceId}/samples/{sampleId}"
                    + "/data/{dataId}/download"
    )
    public ResponseEntity<InputStreamResource> downloadWorkspaceData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId
    ) {
        return stream(
                service.openWorkspaceDataDownload(
                        workspaceId,
                        sampleId,
                        dataId
                ),
                false
        );
    }

    @GetMapping(
            "/dataset-workspaces/{workspaceId}/samples/{sampleId}"
                    + "/annotations/{annotationId}/preview"
    )
    public ResponseEntity<InputStreamResource> previewWorkspaceAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId
    ) {
        return stream(
                service.openWorkspaceAnnotationPreview(
                        workspaceId,
                        sampleId,
                        annotationId
                ),
                true
        );
    }

    @GetMapping(
            "/dataset-workspaces/{workspaceId}/samples/{sampleId}"
                    + "/annotations/{annotationId}/download"
    )
    public ResponseEntity<InputStreamResource> downloadWorkspaceAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId
    ) {
        return stream(
                service.openWorkspaceAnnotationDownload(
                        workspaceId,
                        sampleId,
                        annotationId
                ),
                false
        );
    }

    private ResponseEntity<InputStreamResource> stream(
            SampleFileStream file,
            boolean inline
    ) {
        ResponseEntity.BodyBuilder builder = file.partial()
                ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                : ResponseEntity.ok();
        builder
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(file.fileName(), inline)
                )
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType(file.contentType()));
        if (file.rangeSupported()) {
            builder.header(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
        if (file.partial()) {
            builder.header(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes %d-%d/%d".formatted(
                            file.rangeStart(),
                            file.rangeEnd(),
                            file.totalSize()
                    )
            );
        }
        if (file.sizeBytes() != null && file.sizeBytes() >= 0) {
            builder.contentLength(file.sizeBytes());
        }
        return builder.body(new InputStreamResource(file.inputStream()));
    }

    private static MediaType mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String contentDisposition(String fileName, boolean inline) {
        String safeFileName = safeFileName(fileName);
        ContentDisposition.Builder builder = inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        return builder.filename(safeFileName, StandardCharsets.UTF_8).build().toString();
    }

    private static String safeFileName(String fileName) {
        String value = fileName == null ? "" : fileName;
        int lineBreak = firstLineBreak(value);
        if (lineBreak >= 0) {
            value = value.substring(0, lineBreak);
        }
        value = value.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.isBlank() ? "download" : value;
    }

    private static int firstLineBreak(String value) {
        int carriageReturn = value.indexOf('\r');
        int lineFeed = value.indexOf('\n');
        if (carriageReturn < 0) {
            return lineFeed;
        }
        if (lineFeed < 0) {
            return carriageReturn;
        }
        return Math.min(carriageReturn, lineFeed);
    }
}
