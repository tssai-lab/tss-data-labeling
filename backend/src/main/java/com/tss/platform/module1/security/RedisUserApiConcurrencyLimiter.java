package com.tss.platform.module1.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 跨后端实例共享的用户/功能组并发计数器。异常时由调用方失败关闭，不静默退化为本机计数。 */
@Component
public class RedisUserApiConcurrencyLimiter implements UserApiConcurrencyLimiter {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local redis_time = redis.call('TIME')
            local now_millis = tonumber(redis_time[1]) * 1000
                    + math.floor(tonumber(redis_time[2]) / 1000)
            local limit = tonumber(ARGV[1])
            local ttl_seconds = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_millis)
            if redis.call('ZCARD', KEYS[1]) >= limit then
              return 0
            end
            redis.call('ZADD', KEYS[1], now_millis + ttl_seconds * 1000, ARGV[3])
            redis.call('EXPIRE', KEYS[1], ttl_seconds)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
            end
            return removed
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long counterTtlSeconds;

    public RedisUserApiConcurrencyLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${security.user-api-policy.counter-ttl-seconds:300}") long counterTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.counterTtlSeconds = Math.max(30, counterTtlSeconds);
    }

    @Override
    public Optional<Lease> tryAcquire(Integer userId, UserApiFeatureGroup featureGroup, int limit) {
        if (userId == null || featureGroup == null || limit < 1) {
            throw new IllegalArgumentException("并发控制参数不合法");
        }
        String token = UUID.randomUUID().toString();
        Long result = redisTemplate.execute(
                ACQUIRE,
                List.of(key(userId, featureGroup)),
                String.valueOf(limit),
                String.valueOf(counterTtlSeconds),
                token
        );
        if (result == null) {
            throw new IllegalStateException("并发控制存储无响应");
        }
        return result > 0
                ? Optional.of(new Lease(userId, featureGroup, token))
                : Optional.empty();
    }

    @Override
    public void release(Lease lease) {
        if (lease == null
                || lease.userId() == null
                || lease.featureGroup() == null
                || lease.token() == null
                || lease.token().isBlank()) {
            return;
        }
        redisTemplate.execute(
                RELEASE,
                List.of(key(lease.userId(), lease.featureGroup())),
                lease.token()
        );
    }

    static String key(Integer userId, UserApiFeatureGroup featureGroup) {
        return "tss:user-api-concurrency:" + userId + ":" + featureGroup.name();
    }
}
