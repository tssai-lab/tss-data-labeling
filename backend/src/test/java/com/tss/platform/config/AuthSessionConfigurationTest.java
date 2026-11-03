package com.tss.platform.config;

import cn.dev33.satoken.dao.SaTokenDao;
import com.tss.platform.security.AuthSessionStoreHealth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthSessionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AuthSessionConfiguration.class);

    @Test
    void memoryModeDoesNotInstallTheRedisDaoOrStartupGate() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SaTokenDao.class);
            assertThat(context).doesNotHaveBean(ApplicationRunner.class);
        });
    }

    @Test
    void redisModeInstallsTheRedisDaoAndStartupGate() {
        contextRunner
                .withPropertyValues("auth.session.store=redis")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(AuthSessionStoreHealth.class, () -> mock(AuthSessionStoreHealth.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(SaTokenDao.class);
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                });
    }
}
