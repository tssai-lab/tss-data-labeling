package com.tss.platform.dto.v2;

public record V2DatasetActiveOperation(
        String type,
        String id,
        String status,
        Integer progress
) {
}
