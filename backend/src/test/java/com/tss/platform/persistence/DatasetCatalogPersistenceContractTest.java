package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetCatalogPersistenceContractTest {

    @Test
    void migrationAddsVersionFileCountAndCatalogIndexes() throws Exception {
        String resource = "db/migration/V21__dataset_version_file_count_and_catalog_indexes.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS file_count BIGINT"));
            assertTrue(sql.contains("idx_dataset_asset_catalog_owner_type_time"));
            assertTrue(sql.contains("idx_dataset_asset_catalog_admin_type_time"));
        }

        String readmeResource = "db/migration/README.md";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(readmeResource)) {
            assertNotNull(input, readmeResource);
            String readme = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(readme.contains("V21__dataset_version_file_count_and_catalog_indexes.sql"));
        }
    }
}
