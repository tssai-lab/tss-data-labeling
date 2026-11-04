package com.tss.platform.dto.v2;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record V2CodeAssetImportMetadata(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 64) String version,
        @NotBlank @Size(max = 128) String trainingProfile,
        @Size(max = 1024) String purpose,
        @Size(max = 128) String runtime,
        @Size(max = 1024) String entryScript,
        @Size(max = 128) String trainingType,
        @Size(max = 1024) String remark
) {
}
