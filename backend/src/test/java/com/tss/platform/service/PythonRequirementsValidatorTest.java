package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PythonRequirementsValidatorTest {

    private final PythonRequirementsValidator validator = new PythonRequirementsValidator();

    @Test
    void normalizesAndFingerprintsDeterministicRequirements() {
        var manifest = validator.parse("torch==2.5.1\nultralytics >= 8.3.18 # comment\n"
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(java.util.List.of("torch==2.5.1", "ultralytics>=8.3.18"), manifest.requirements());
        org.junit.jupiter.api.Assertions.assertEquals(64, manifest.sha256().length());
    }

    @Test
    void rejectsDirectUrlAndPipOptions() {
        assertThrows(CodeValidationException.class,
                () -> validator.parse("-r other.txt".getBytes(StandardCharsets.UTF_8)));
        assertThrows(CodeValidationException.class,
                () -> validator.parse("torch @ https://example.test/torch.whl".getBytes(StandardCharsets.UTF_8)));
    }
}
