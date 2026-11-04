package com.tss.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Module-2-owned, read-only reference probe. It deliberately queries persisted
 * identifiers rather than depending on training services or entities.
 */
@Component
public class CodeAssetReferenceChecker {

    private static final String TRAINING_REFERENCE_EXISTS_SQL = """
            SELECT CASE WHEN EXISTS (
                SELECT 1
                FROM training_experiment_version training_version
                JOIN code_version code_version_row
                  ON code_version_row.id = training_version.code_version_id
                WHERE code_version_row.asset_id = ?
            ) THEN 1 ELSE 0 END
            """;

    private final JdbcTemplate jdbcTemplate;

    public CodeAssetReferenceChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasReferences(String assetId) {
        Integer result = jdbcTemplate.queryForObject(
                TRAINING_REFERENCE_EXISTS_SQL,
                Integer.class,
                assetId
        );
        return result != null && result == 1;
    }
}
