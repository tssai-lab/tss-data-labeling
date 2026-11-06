package com.tss.labeling.imports;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("labeling.imports")
public record ImportSettings(
        @DefaultValue("./data/labeling") String storageRoot,
        @DefaultValue("4194304") @Min(1) @Max(2147483646) int chunkSizeBytes,
        @DefaultValue("2147483648") @Min(1) long maxArchiveBytes,
        @DefaultValue("8589934592") @Min(1) long maxExpandedBytes,
        @DefaultValue("67108864") @Min(1) long maxFileBytes,
        @DefaultValue("100000") @Min(1) int maxEntries,
        @DefaultValue("40000000") @Min(1) long maxPixels,
        @DefaultValue("1000") @Min(1) int maxErrors,
        @DefaultValue("UTF-8") String zipEncoding,
        @DefaultValue("true") boolean workerEnabled
) {
    public ImportSettings { java.nio.charset.Charset.forName(zipEncoding); }
}
