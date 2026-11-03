package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherAssetCategoryV59MigrationContractTest {

    @Test
    void migrationOnlyAddsOtherToTheThreeExistingCategoryChecks() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V59__add_other_asset_category.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String upper = sql.toUpperCase();

        assertTrue(sql.contains("ck_model_asset_type"));
        assertTrue(sql.contains("ck_dataset_asset_type"));
        assertTrue(sql.contains("ck_dataset_upload_session_task_type"));
        assertEquals(3, occurrences(upper, "'OTHER'"));
        assertFalse(upper.contains("UPDATE "));
        assertFalse(upper.contains("DELETE "));
        assertFalse(upper.contains("DROP COLUMN"));
        assertFalse(upper.contains("ADD COLUMN"));
        assertFalse(upper.contains("CREATE TABLE"));
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
