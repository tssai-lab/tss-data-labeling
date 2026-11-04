package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceExactGpuV67MigrationContractTest {

    @Test
    void migrationKeepsCpuRowsNullableAndConstrainsGpuSnapshots() throws Exception {
        String sql = new ClassPathResource("db/migration/V67__inference_exact_gpu_selection.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains("add column if not exists hardware_target_id");
        assertThat(sql).contains("add column if not exists gpu_node_name");
        assertThat(sql).contains("add column if not exists gpu_host_index");
        assertThat(sql).contains("add column if not exists gpu_uuid");
        assertThat(sql).contains("add column if not exists gpu_model");
        assertThat(sql).contains("add column if not exists gpu_total_memory_mib");
        assertThat(sql).contains("add column if not exists gpu_memory_limit_mib");
        assertThat(sql).contains("gpu_uuid is null and gpu_node_name is null");
        assertThat(sql).contains("hardware_target_id is not null and gpu_uuid is not null");
        assertThat(sql).contains("gpu_memory_limit_mib <= gpu_total_memory_mib");
        assertThat(sql).contains("create index if not exists idx_inference_task_gpu_uuid");
        assertThat(sql).doesNotContain("update inference_task");
    }
}
