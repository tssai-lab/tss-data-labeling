package com.tss.platform.dto.v2;

import java.util.List;

public record V2AdminCodeAssetPage(
        List<V2AdminCodeAssetDto> items,
        int page,
        int pageSize,
        long totalElements,
        int totalPages
) {
}
