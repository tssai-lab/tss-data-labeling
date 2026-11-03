package com.tss.platform.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import com.tss.platform.security.AuthSessionStoreHealth;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
public class AuthSessionConfiguration {

    @Bean
    @ConditionalOnMissingBean(SaTokenDao.class)
    @ConditionalOnProperty(prefix = "auth.session", name = "store", havingValue = "redis")
    SaTokenDao redisSaTokenDao(RedisConnectionFactory connectionFactory) {
        SaTokenDaoRedisJackson dao = new SaTokenDaoRedisJackson();
        dao.init(connectionFactory);
        return dao;
    }

    @Bean
    @ConditionalOnProperty(prefix = "auth.session", name = "store", havingValue = "redis")
    ApplicationRunner verifyRedisSessionStoreOnStartup(AuthSessionStoreHealth health) {
        return args -> health.assertReady();
    }
}
