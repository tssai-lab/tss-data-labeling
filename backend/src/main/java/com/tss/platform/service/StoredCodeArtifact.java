package com.tss.platform.service;

import java.util.Arrays;
import java.util.Objects;

public record StoredCodeArtifact(
        String objectName,
        byte[] bytes,
        String artifactSha256,
        long sizeBytes
) {
    public StoredCodeArtifact {
        Objects.requireNonNull(objectName, "objectName");
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        if (sizeBytes != bytes.length) {
            throw new IllegalArgumentException("sizeBytes must match bytes");
        }
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
