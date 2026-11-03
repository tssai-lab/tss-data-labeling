package com.tss.platform.module1.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUserApiConcurrencyLimiterTest {

    @Test
    void usesAnIndependentRedisLeaseForAcquireAndRelease() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        RedisUserApiConcurrencyLimiter limiter = new RedisUserApiConcurrencyLimiter(redis, 300);

        UserApiConcurrencyLimiter.Lease lease = limiter
                .tryAcquire(7, UserApiFeatureGroup.INFERENCE_TASK, 2)
                .orElseThrow();
        assertThat(lease.token()).isNotBlank();
        limiter.release(lease);

        verify(redis).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "tss:user-api-concurrency:7:INFERENCE_TASK")),
                org.mockito.ArgumentMatchers.eq("2"),
                org.mockito.ArgumentMatchers.eq("300"),
                org.mockito.ArgumentMatchers.eq(lease.token())
        );
        verify(redis).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(
                        "tss:user-api-concurrency:7:INFERENCE_TASK")),
                org.mockito.ArgumentMatchers.eq(lease.token())
        );
    }

    @Test
    void eachRequestGetsADifferentLeaseSoAnOldRequestCannotReleaseANewSlot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        RedisUserApiConcurrencyLimiter limiter = new RedisUserApiConcurrencyLimiter(redis, 300);

        UserApiConcurrencyLimiter.Lease first = limiter
                .tryAcquire(7, UserApiFeatureGroup.TRAINING_TASK, 2)
                .orElseThrow();
        UserApiConcurrencyLimiter.Lease second = limiter
                .tryAcquire(7, UserApiFeatureGroup.TRAINING_TASK, 2)
                .orElseThrow();

        assertThat(first.token()).isNotEqualTo(second.token());
        verify(redis, times(2)).execute(
                any(RedisScript.class),
                anyList(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void redisFailureDoesNotSilentlyFallBackToPerProcessCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);
        RedisUserApiConcurrencyLimiter limiter = new RedisUserApiConcurrencyLimiter(redis, 300);

        assertThatThrownBy(() -> limiter.tryAcquire(7, UserApiFeatureGroup.TRAINING_TASK, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无响应");
    }
}
