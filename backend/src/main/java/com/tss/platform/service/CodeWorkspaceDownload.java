package com.tss.platform.service;

import java.util.Arrays;
import java.util.Objects;

/** Bounded downloadable workspace file bytes and their public-safe descriptor. */
public record CodeWorkspaceDownload(CodeFileDescriptor descriptor, byte[] bytes) {

    public CodeWorkspaceDownload {
        Objects.requireNonNull(descriptor, "descriptor");
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
