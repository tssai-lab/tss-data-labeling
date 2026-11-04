package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactSpecV58MigrationContractTest {

    @Test
    void migrationIsNullablePrefixConstrainedAndDoesNotBackfillHistory() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V58__artifact_spec_evidence.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertEquals(4, occurrences(sql, "ADD COLUMN artifact_spec_id VARCHAR(128)"));
        assertTrue(sql.contains("ck_model_version_artifact_spec_id"));
        assertTrue(sql.contains("ck_model_upload_session_artifact_spec_id"));
        assertTrue(sql.contains("ck_dataset_version_artifact_spec_id"));
        assertTrue(sql.contains("ck_dataset_upload_session_artifact_spec_id"));
        assertTrue(sql.contains("idx_model_version_ready_artifact_spec"));
        assertTrue(sql.contains("idx_dataset_version_ready_artifact_spec"));
        assertTrue(sql.contains("artifact_spec_id IS NOT NULL"));
        assertFalse(sql.toUpperCase().contains("UPDATE MODEL_VERSION"));
        assertFalse(sql.toUpperCase().contains("UPDATE DATASET_VERSION"));
        assertFalse(sql.toUpperCase().contains("DROP COLUMN"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count += 1;
            offset += needle.length();
        }
        return count;
    }
}
