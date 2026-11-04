package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullableServerMetricsV64MigrationContractTest {

    @Test
    void migrationOnlyMakesMetricColumnsNullableWithoutRewritingHistory() throws Exception {
        String sql = new ClassPathResource("db/migration/V64__nullable_server_metrics.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        for (String column : new String[]{
                "cpu_rate", "mem_rate", "gpu_rate", "gpu_mem_rate",
                "disk_rate", "network_in", "network_out", "gpu_temp"
        }) {
            assertTrue(sql.contains("alter column " + column + " drop default"));
            assertTrue(sql.contains("alter column " + column + " drop not null"));
        }
        assertFalse(sql.contains("update "));
        assertFalse(sql.contains("delete "));
        assertFalse(sql.contains("drop table"));
    }
}
