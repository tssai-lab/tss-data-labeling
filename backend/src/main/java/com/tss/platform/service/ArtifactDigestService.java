package com.tss.platform.service;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Calculates the digest from the object store, so callers never trust client supplied hashes. */
@Service
public class ArtifactDigestService {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final MinioService minioService;

    public ArtifactDigestService(MinioService minioService) {
        this.minioService = minioService;
    }

    public DigestResult digest(String objectName, Long expectedSize) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("artifact objectName cannot be empty");
        }
        if (expectedSize == null || expectedSize <= 0) {
            throw new IllegalArgumentException("artifact sizeBytes must be greater than zero");
        }
        try (InputStream input = minioService.downloadStream(objectName)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                size += read;
            }
            if (size != expectedSize) {
                throw new IllegalArgumentException(
                        "artifact size mismatch: expected=" + expectedSize + ", actual=" + size
                );
            }
            return new DigestResult(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to calculate artifact SHA-256: " + e.getMessage(), e);
        }
    }

    public record DigestResult(String sha256, long sizeBytes) {
    }
}
