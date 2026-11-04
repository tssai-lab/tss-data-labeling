package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipSafetyValidatorTest {

    @Test
    void rejectsMoreThanOneHundredThousandEntries() {
        ZipEntryInfo entry = entry("data.bin", 1);
        List<ZipEntryInfo> entries = Collections.nCopies(100_001, entry);

        assertThrows(IllegalArgumentException.class, () -> ZipSafetyValidator.validate(entries));
    }

    @Test
    void rejectsAggregateUncompressedSizeAboveFiftyGigabytes() {
        long thirtyGigabytes = 30L * 1024 * 1024 * 1024;
        List<ZipEntryInfo> entries = List.of(
                entry("first.bin", thirtyGigabytes),
                entry("second.bin", thirtyGigabytes)
        );

        assertThrows(IllegalArgumentException.class, () -> ZipSafetyValidator.validate(entries));
    }

    private static ZipEntryInfo entry(String path, long uncompressedSize) {
        return new ZipEntryInfo(
                path,
                path,
                0,
                uncompressedSize,
                uncompressedSize,
                0,
                0,
                30,
                false,
                false
        );
    }
}
