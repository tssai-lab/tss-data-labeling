package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

@Service
public class ManifestZipReader {

    private static final long MAX_MANIFEST_SIZE = 10L * 1024 * 1024;

    private final ZipCentralDirectoryReader centralDirectoryReader;
    private final MinioService minioService;

    public ManifestZipReader(
            ZipCentralDirectoryReader centralDirectoryReader,
            MinioService minioService
    ) {
        this.centralDirectoryReader = centralDirectoryReader;
        this.minioService = minioService;
    }

    public String readManifest(
            String objectName,
            long objectSize,
            String manifestPath
    ) throws Exception {
        String safePath;
        String normalizedPath;
        try {
            safePath = DatasetUploadService.normalizeManifestPath(
                    "MANIFEST",
                    manifestPath
            );
            normalizedPath = ZipCentralDirectoryReader.normalizePath(safePath);
        } catch (IllegalArgumentException exception) {
            throw invalidManifest(
                    "manifestPath",
                    manifestPath,
                    safeReason(exception, "manifest path is invalid"),
                    exception
            );
        }
        List<ZipEntryInfo> entries = centralDirectoryReader.read(objectName, objectSize);
        ZipEntryInfo manifest = entries.stream()
                .filter(entry -> normalizedPath.equals(entry.normalizedPath()))
                .findFirst()
                .orElseThrow(() -> invalidManifest(
                        "manifestPath",
                        normalizedPath,
                        "manifest entry not found",
                        null
                ));

        if (manifest.directory()) {
            throw invalidManifest(
                    "manifestPath",
                    normalizedPath,
                    "manifest path points to a directory",
                    null
            );
        }
        if (manifest.uncompressedSize() > MAX_MANIFEST_SIZE) {
            throw invalidManifest(
                    "manifestPath",
                    normalizedPath,
                    "manifest exceeds 10MB",
                    null
            );
        }

        try (InputStream range = minioService.downloadRange(
                objectName,
                manifest.zipDataOffset(),
                manifest.compressedSize()
        )) {
            if (manifest.method() == 0) {
                return decodeBounded(
                        range,
                        manifest.uncompressedSize(),
                        normalizedPath
                );
            }
            if (manifest.method() == 8) {
                try (InflaterInputStream inflater = new InflaterInputStream(range, new Inflater(true))) {
                    return decodeBounded(
                            inflater,
                            manifest.uncompressedSize(),
                            normalizedPath
                    );
                }
            }
            throw invalidManifest(
                    ManifestFailureKind.UNSUPPORTED_SAMPLE_FILE,
                    "manifestPath",
                    normalizedPath,
                    "unsupported manifest compression method: " + manifest.method(),
                    null
            );
        }
    }

    private static String decodeBounded(
            InputStream input,
            long declaredSize,
            String manifestPath
    ) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(declaredSize, MAX_MANIFEST_SIZE));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_MANIFEST_SIZE) {
                throw invalidManifest(
                        "manifestPath",
                        manifestPath,
                        "manifest exceeds 10MB",
                        null
                );
            }
            output.write(buffer, 0, read);
        }
        if (total != declaredSize) {
            throw invalidManifest(
                    "manifestPath",
                    manifestPath,
                    "manifest size does not match ZIP metadata",
                    null
            );
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static ManifestValidationException invalidManifest(
            String field,
            String path,
            String reason,
            Throwable cause
    ) {
        return invalidManifest(ManifestFailureKind.INVALID_MANIFEST, field, path, reason, cause);
    }

    private static ManifestValidationException invalidManifest(ManifestFailureKind kind,
            String field, String path, String reason, Throwable cause) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        if (path != null && !path.isBlank()) {
            details.put("path", path);
        }
        details.put("reason", reason);
        String message = "field " + field
                + (path == null || path.isBlank() ? "" : ", path: " + path)
                + ", reason: " + reason;
        return ManifestValidationException.classified(
                kind,
                message,
                Map.copyOf(details),
                cause
        );
    }

    private static String safeReason(
            IllegalArgumentException exception,
            String fallback
    ) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
