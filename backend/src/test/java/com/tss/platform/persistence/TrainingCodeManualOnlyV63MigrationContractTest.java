package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingCodeManualOnlyV63MigrationContractTest {

    @Test
    void migrationOnlyExtendsTheExistingReviewModeConstraint() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V63__training_code_manual_only_mode.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String upper = sql.toUpperCase();

        assertTrue(upper.contains("DROP CONSTRAINT CK_PLATFORM_SYSTEM_CONFIG_TRAINING_CODE_REVIEW_MODE"));
        assertTrue(upper.contains("'DIRECT_PASS'"));
        assertTrue(upper.contains("'STANDARD_REVIEW'"));
        assertTrue(upper.contains("'MANUAL_ONLY'"));
        assertFalse(upper.contains("UPDATE "));
        assertFalse(upper.contains("DELETE "));
        assertFalse(upper.contains("DROP TABLE"));
        assertFalse(upper.contains("DROP COLUMN"));
    }
}
