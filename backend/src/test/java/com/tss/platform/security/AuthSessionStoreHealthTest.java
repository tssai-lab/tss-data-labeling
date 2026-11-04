package com.tss.platform.security;

import com.tss.platform.config.AuthSessionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthSessionStoreHealthTest {

    @Test
    void memoryStoreIsExplicitAndDoesNotTouchRedis() {
        AuthSessionProperties properties = new AuthSessionProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        AuthSessionStoreHealth health = new AuthSessionStoreHealth(properties, provider);

        assertEquals("MEMORY", health.readinessStatus());
        health.assertReady();

        verifyNoInteractions(provider);
    }

    @Test
    void redisStoreRequiresPong() {
        AuthSessionProperties properties = new AuthSessionProperties();
        properties.setStore(AuthSessionProperties.Store.REDIS);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(provider.getIfAvailable()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        AuthSessionStoreHealth health = new AuthSessionStoreHealth(properties, provider);

        health.assertReady();

        assertEquals("UP", health.readinessStatus());
    }

    @Test
    void redisStoreFailsClosedWithoutAConnectionFactory() {
        AuthSessionProperties properties = new AuthSessionProperties();
        properties.setStore(AuthSessionProperties.Store.REDIS);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AuthSessionStoreHealth health = new AuthSessionStoreHealth(properties, provider);

        assertThrows(IllegalStateException.class, health::assertReady);
        assertEquals("DOWN", health.readinessStatus());
    }
}
