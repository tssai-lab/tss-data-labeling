package com.tss.platform.dto.v2;

public record V2ModelFileNode(
        String path,
        String name,
        String nodeType,
        Long sizeBytes
) {
}
