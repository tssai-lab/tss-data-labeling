package com.tss.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

@Service
public class CodeArtifactStorageService {

    private static final Logger log = LoggerFactory.getLogger(CodeArtifactStorageService.class);
    private static final long MAX_STORED_BYTES = 512L * 1024L * 1024L;

    private final MinioService minioService;
    private final CodeFilePolicy filePolicy;

    public CodeArtifactStorageService(MinioService minioService, CodeFilePolicy filePolicy) {
        this.minioService = minioService;
        this.filePolicy = filePolicy;
    }

    public void upload(String objectName, byte[] bytes) {
        byte[] safeBytes = Arrays.copyOf(requireBytes(bytes), bytes.length);
        requireObjectName(objectName);
        try (InputStream input = new ByteArrayInputStream(safeBytes)) {
            minioService.uploadStream(
                    objectName,
                    input,
                    safeBytes.length,
                    MediaType.parseMediaType("application/zip").toString()
            );
        } catch (Exception exception) {
            log.warn("Code artifact upload failed: errorType={}", exception.getClass().getSimpleName());
            throw new CodeArtifactStorageException();
        }
    }

    public StoredCodeArtifact read(String objectName) {
        requireObjectName(objectName);
        try (InputStream input = minioService.downloadStream(objectName)) {
            byte[] bytes = readBounded(input);
            return new StoredCodeArtifact(
                    objectName,
                    bytes,
                    filePolicy.sha256(bytes),
                    bytes.length
            );
        } catch (Exception exception) {
            log.warn("Code artifact read failed: errorType={}", exception.getClass().getSimpleName());
            throw new CodeArtifactStorageException();
        }
    }

    public void delete(String objectName) {
        requireObjectName(objectName);
        try {
            minioService.deleteObject(objectName);
        } catch (Exception exception) {
            log.warn("Code artifact best-effort delete failed: errorType={}",
                    exception.getClass().getSimpleName());
            throw new CodeArtifactStorageException();
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > MAX_STORED_BYTES - total) {
                throw new IllegalStateException("Stored artifact exceeds limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static byte[] requireBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Code artifact bytes are required");
        }
        return bytes;
    }

    private static void requireObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("Code artifact object name is required");
        }
    }
}
