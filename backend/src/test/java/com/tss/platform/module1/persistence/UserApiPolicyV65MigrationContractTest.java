package com.tss.platform.module1.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class UserApiPolicyV65MigrationContractTest {

    @Test
    void migrationAddsSparseUserOverridesWithoutBackfillingExistingUsers() throws Exception {
        String sql = new ClassPathResource("db/migration/V65__user_api_policies.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains("create table user_api_policies");
        assertThat(sql).contains("unique (user_id, feature_group)");
        assertThat(sql).contains("max_concurrent_requests is null or max_concurrent_requests >= 1");
        assertThat(sql).contains("version bigint not null default 0");
        assertThat(sql).contains("to_regclass('public.users')");
        assertThat(sql).contains("foreign key (user_id) references users(id)");
        assertThat(sql).contains("foreign key (updated_by) references users(id)");
        assertThat(sql).doesNotContain("user_id integer not null references users(id)");
        assertThat(sql).doesNotContain("insert into user_api_policies");
        assertThat(sql).doesNotContain("update users");
        for (String group : new String[]{
                "model_asset",
                "dataset_asset",
                "training_definition",
                "training_task",
                "inference_task",
                "system_admin_audit"
        }) {
            assertThat(sql).contains("'" + group + "'");
        }
    }
}
