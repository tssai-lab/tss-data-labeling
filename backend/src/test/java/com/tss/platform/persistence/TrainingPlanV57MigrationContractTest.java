package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanV57MigrationContractTest {

    @Test
    void migrationKeepsImmutableVersionAndSingleActivePlanConstraints() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V57__online_training_plan_definition.sql")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertTrue(sql.contains("UNIQUE (plan_id, plan_version)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_training_plan_definition_active"));
        assertTrue(sql.contains("WHERE status = 'ACTIVE'"));
        assertTrue(sql.contains("ck_training_plan_definition_disabled_evidence"));
        assertTrue(sql.contains("content_sha256 ~ '^[0-9a-f]{64}$'"));
        assertTrue(sql.contains("content_sha256 VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("yaml_content TEXT NOT NULL"));
        assertTrue(sql.contains("schema_version = 'tss.training.plan/v2'"));
        assertTrue(sql.contains("OCTET_LENGTH(yaml_content) BETWEEN 1 AND 262144"));
    }
}
