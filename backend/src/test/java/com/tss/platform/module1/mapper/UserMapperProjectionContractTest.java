package com.tss.platform.module1.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserMapperProjectionContractTest {

    @Test
    void listAndDetailQueriesNeverProjectPasswordHash() throws Exception {
        assertSafeProjection(UserMapper.class.getMethod("selectUserWithRole", Integer.class));
        assertSafeProjection(UserMapper.class.getMethod(
                "selectUserDetail", Integer.class, Integer.class));

        try (InputStream input = getClass().getResourceAsStream("/mapper/UserMapper.xml")) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("u.*");
            assertThat(xml).doesNotContain("password");
            assertThat(xml).contains("requiredroleid");
        }
    }

    private static void assertSafeProjection(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).toLowerCase();
        assertThat(sql).doesNotContain("u.*");
        assertThat(sql).doesNotContain("password");
        assertThat(sql).contains("requiredroleid");
    }
}
