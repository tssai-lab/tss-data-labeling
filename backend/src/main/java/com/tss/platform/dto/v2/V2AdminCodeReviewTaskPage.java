package com.tss.platform.dto.v2;

import java.util.List;

public record V2AdminCodeReviewTaskPage(
        List<V2AdminCodeReviewTask> items,
        int page,
        int pageSize,
        long totalElements,
        int totalPages
) {

    public V2AdminCodeReviewTaskPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
