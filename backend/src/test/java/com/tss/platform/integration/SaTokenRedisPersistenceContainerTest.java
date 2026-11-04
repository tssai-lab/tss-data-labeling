package com.tss.platform.integration;

import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import cn.dev33.satoken.session.SaSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class SaTokenRedisPersistenceContainerTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.11-alpine");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(REDIS);
    }

    @Test
    void tokenAndSessionRemainAfterDaoRecreation() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        String tokenKey = "sp:login:token:restart-test-token";
        String sessionKey = "sp:session:restart-test-session";
        try {
            SaTokenDaoRedisJackson beforeRestart = dao(connectionFactory);
            beforeRestart.set(tokenKey, "7", 120);
            SaSession session = new SaSession("restart-test-session");
            session.set("roleId", 3);
            session.set("username", "restart-test-user");
            beforeRestart.setObject(sessionKey, session, 120);

            SaTokenDaoRedisJackson afterRestart = dao(connectionFactory);

            assertEquals("7", afterRestart.get(tokenKey));
            SaSession restored = assertInstanceOf(SaSession.class, afterRestart.getObject(sessionKey));
            assertEquals(3, restored.get("roleId"));
            assertEquals("restart-test-user", restored.get("username"));
            assertTrue(afterRestart.getTimeout(tokenKey) > 0);
            assertTrue(afterRestart.getObjectTimeout(sessionKey) > 0);
        } finally {
            SaTokenDaoRedisJackson cleanup = dao(connectionFactory);
            cleanup.delete(tokenKey);
            cleanup.deleteObject(sessionKey);
            connectionFactory.destroy();
        }
    }

    private SaTokenDaoRedisJackson dao(LettuceConnectionFactory connectionFactory) {
        SaTokenDaoRedisJackson dao = new SaTokenDaoRedisJackson();
        dao.init(connectionFactory);
        return dao;
    }
}
