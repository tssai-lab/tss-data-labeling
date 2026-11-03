package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.MinioService;
import io.minio.StatObjectResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Downloads the immutable dataset package belonging to a dataset version. */
@RestController
@RequestMapping("/api/dataset-versions")
public class DatasetVersionDownloadController {

    private final DatasetVersionRepository versionRepository;
    private final DatasetAssetRepository assetRepository;
    private final AuthContext authContext;
    private final MinioService minioService;

    public DatasetVersionDownloadController(
            DatasetVersionRepository versionRepository,
            DatasetAssetRepository assetRepository,
            AuthContext authContext,
            MinioService minioService
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.authContext = authContext;
        this.minioService = minioService;
    }

    @GetMapping("/{versionId}/download")
    public ResponseEntity<?> download(@PathVariable String versionId) {
        try {
            DatasetVersion version = versionRepository.findByIdAndDeletedFalse(versionId)
                    .orElseThrow(() -> new IllegalArgumentException("dataset version not found"));
            DatasetAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                    .orElseThrow(() -> new IllegalArgumentException("dataset asset not found"));
            if (!authContext.canAccessOwner(asset.getOwnerUserId())
                    || version.getStoragePath() == null || version.getStoragePath().isBlank()) {
                throw new IllegalArgumentException("dataset version not found or no permission");
            }
            StatObjectResponse stat = minioService.stat(version.getStoragePath());
            String fileName = safeFileName(version.getFileName());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(stat.size())
                    .body(new InputStreamResource(minioService.downloadStream(version.getStoragePath())));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.fail("dataset package download failed"));
        }
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "dataset.zip";
        }
        return fileName.replace('\\', '_').replace('/', '_');
    }
}
