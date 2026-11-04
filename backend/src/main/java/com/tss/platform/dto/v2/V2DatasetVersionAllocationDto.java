package com.tss.platform.dto.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record V2DatasetVersionAllocationDto(
        Integer nextVersionNo,
        String defaultVersionLabel,
        String requestedVersionLabel,
        Boolean requestedVersionLabelAvailable,
        String unavailableReason
) {
}
