package com.tss.platform.module1.security;

import java.util.Optional;

public interface UserApiConcurrencyLimiter {
    Optional<Lease> tryAcquire(Integer userId, UserApiFeatureGroup featureGroup, int limit);

    void release(Lease lease);

    record Lease(Integer userId, UserApiFeatureGroup featureGroup, String token) {
    }
}
