package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetContentPreviewDto;
import com.tss.platform.dto.DatasetPreviewFileListDto;
import com.tss.platform.service.DatasetPreviewService;
import com.tss.platform.service.DatasetPreviewService.DatasetImageStream;
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
@RequestMapping("/api/dataset/preview")
public class DatasetPreviewController {

    private final DatasetPreviewService previewService;

    public DatasetPreviewController(DatasetPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/files")
    public ApiResponse<DatasetPreviewFileListDto> files(
            @RequestParam("id") String id,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "kind", required = false) String kind
    ) {
        try {
            return ApiResponse.ok(previewService.listFiles(id, page, pageSize, keyword, kind));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/content")
    public ApiResponse<DatasetContentPreviewDto> content(
            @RequestParam("id") String id,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        try {
            return ApiResponse.ok(previewService.previewContent(id, path, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/image")
    public ResponseEntity<?> image(
            @RequestParam("id") String id,
            @RequestParam("path") String path
    ) {
        try {
            DatasetImageStream image = previewService.openImage(id, path);
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(image.fileName()))
                    .contentType(MediaType.parseMediaType(image.contentType()));
            if (image.sizeBytes() != null && image.sizeBytes() >= 0) {
                builder.contentLength(image.sizeBytes());
            }
            return builder.body(new InputStreamResource(image.inputStream()));
        } catch (IllegalArgumentException e) {
            return error(e);
        }
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
        String encoded = URLEncoder.encode(fileName == null ? "dataset-image" : fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "inline; filename*=UTF-8''" + encoded;
    }
}
