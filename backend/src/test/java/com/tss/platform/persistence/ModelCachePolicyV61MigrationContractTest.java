package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCachePolicyV61MigrationContractTest {

    @Test
    void migrationAddsBoundedIntegerGiBPolicyWithoutTouchingExistingRows() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V61__model_cache_disk_policy.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        String upper = sql.toUpperCase();

        assertTrue(sql.contains("model_cache_max_bytes BIGINT NOT NULL DEFAULT 1073741824"));
        assertTrue(sql.contains("model_cache_min_free_bytes BIGINT NOT NULL DEFAULT 3221225472"));
        assertTrue(sql.contains("model_cache_runtime_reserve_bytes BIGINT NOT NULL DEFAULT 10737418240"));
        assertTrue(sql.contains("MOD(model_cache_max_bytes, 1073741824) = 0"));
        assertTrue(sql.contains("MOD(model_cache_min_free_bytes, 1073741824) = 0"));
        assertTrue(sql.contains("MOD(model_cache_runtime_reserve_bytes, 1073741824) = 0"));
        assertFalse(upper.contains("UPDATE "));
        assertFalse(upper.contains("DELETE "));
        assertFalse(upper.contains("DROP COLUMN"));
        assertFalse(upper.contains("DROP TABLE"));
    }
}
