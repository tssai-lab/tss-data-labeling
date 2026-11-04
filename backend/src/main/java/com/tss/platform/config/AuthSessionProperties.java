package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "auth.session")
public class AuthSessionProperties {

    private Store store = Store.MEMORY;

    public boolean isRedis() {
        return store == Store.REDIS;
    }

    public enum Store {
        MEMORY,
        REDIS
    }
}
