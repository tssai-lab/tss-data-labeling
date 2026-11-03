package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.PointCloudPreviewDto;
import com.tss.platform.service.PointCloudPreviewService;
import com.tss.platform.service.PointCloudPreviewService.PointCloudFileStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/dataset/point-cloud")
public class PointCloudPreviewController {

    private final PointCloudPreviewService previewService;

    public PointCloudPreviewController(PointCloudPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/preview")
    public ApiResponse<PointCloudPreviewDto> preview(@RequestParam("id") String id) {
        try {
            return ApiResponse.ok(previewService.preview(id));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/file")
    public ResponseEntity<?> file(@RequestParam("id") String id) {
        try {
            return stream(previewService.openPointCloudFile(id));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/zip-file")
    public ResponseEntity<?> zipFile(
            @RequestParam("id") String id,
            @RequestParam("path") String path
    ) {
        try {
            return stream(previewService.openZipPointCloudFile(id, path));
        } catch (Exception e) {
            return error(e);
        }
    }

    private ResponseEntity<InputStreamResource> stream(PointCloudFileStream file) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(file.fileName()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (file.sizeBytes() != null && file.sizeBytes() >= 0) {
            builder.contentLength(file.sizeBytes());
        }
        return builder.body(new InputStreamResource(file.inputStream()));
    }

    private ResponseEntity<ApiResponse<Object>> error(Exception e) {
        HttpStatus status = e.getMessage() != null && e.getMessage().contains("no permission")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(e.getMessage()));
    }

    private String inlineDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName == null ? "point-cloud" : fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "inline; filename*=UTF-8''" + encoded;
    }
}
