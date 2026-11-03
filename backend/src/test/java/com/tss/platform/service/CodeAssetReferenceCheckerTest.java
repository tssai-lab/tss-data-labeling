package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAssetReferenceCheckerTest {

    @Test
    void checksPersistedVersionReferencesWithoutTrainingServiceDependency() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CodeAssetReferenceChecker checker = new CodeAssetReferenceChecker(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("asset-1")))
                .thenReturn(1, 0);

        assertTrue(checker.hasReferences("asset-1"));
        assertFalse(checker.hasReferences("asset-1"));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForObject(
                sql.capture(), eq(Integer.class), eq("asset-1")
        );
        org.assertj.core.api.Assertions.assertThat(sql.getValue())
                .contains("training_experiment_version")
                .contains("code_version")
                .contains("asset_id")
                .doesNotContain("storage_path");
    }
}
