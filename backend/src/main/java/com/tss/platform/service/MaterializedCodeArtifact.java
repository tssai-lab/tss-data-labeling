package com.tss.platform.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record MaterializedCodeArtifact(
        byte[] bytes,
        Map<String, byte[]> files,
        CodeValidationResult validation
) {
    public MaterializedCodeArtifact {
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        files = copyFiles(files);
        Objects.requireNonNull(validation, "validation");
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public Map<String, byte[]> files() {
        return copyFiles(files);
    }

    private static Map<String, byte[]> copyFiles(Map<String, byte[]> source) {
        Objects.requireNonNull(source, "files");
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        source.forEach((path, content) -> copy.put(
                Objects.requireNonNull(path, "file path"),
                Arrays.copyOf(Objects.requireNonNull(content, "file content"), content.length)
        ));
        return Collections.unmodifiableMap(copy);
    }
}
