package com.tss.platform.security;

import com.tss.platform.config.AuthSessionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthSessionStoreHealth {

    private final AuthSessionProperties properties;
    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    public AuthSessionStoreHealth(
            AuthSessionProperties properties,
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider
    ) {
        this.properties = properties;
        this.connectionFactoryProvider = connectionFactoryProvider;
    }

    public String readinessStatus() {
        if (!properties.isRedis()) {
            return "MEMORY";
        }
        try {
            assertReady();
            return "UP";
        } catch (RuntimeException exception) {
            return "DOWN";
        }
    }

    public void assertReady() {
        if (!properties.isRedis()) {
            return;
        }
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            throw new IllegalStateException("Redis session store is enabled but no connection factory is available");
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                throw new IllegalStateException("Redis session store did not return PONG");
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Redis session store is unavailable", exception);
        }
    }
}
