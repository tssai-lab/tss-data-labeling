package com.tss.platform.dto;

import lombok.Builder;
import lombok.Data;

/** Immutable object reference used by a single-file inference task. */
@Data
@Builder
public class InferenceInputUploadDto {
    private String objectName;
    private String fileName;
    private Long sizeBytes;
}
