package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceResourceProfileV62MigrationContractTest {

    @Test
    void migrationAddsNullableProfileWithoutRewritingHistoricalTasks() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V62__inference_resource_profile.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String upper = sql.toUpperCase();

        assertTrue(upper.contains("ADD COLUMN IF NOT EXISTS RESOURCE_PROFILE_ID VARCHAR(64)"));
        assertFalse(upper.contains("NOT NULL"));
        assertFalse(upper.contains("UPDATE "));
        assertFalse(upper.contains("DELETE "));
        assertFalse(upper.contains("DROP "));
    }
}
