package com.tss.platform.module1.security;

import java.time.LocalDateTime;

public record UserApiPolicyDecision(
        Integer userId,
        UserApiFeatureGroup featureGroup,
        String displayName,
        boolean enabled,
        Integer maxConcurrentRequests,
        boolean inherited,
        Long version,
        LocalDateTime updatedAt
) {
    public static UserApiPolicyDecision inherited(Integer userId, UserApiFeatureGroup group) {
        return new UserApiPolicyDecision(
                userId, group, group.getDisplayName(), true, null, true, null, null);
    }

    public static UserApiPolicyDecision explicit(
            Integer userId,
            UserApiFeatureGroup group,
            boolean enabled,
            Integer maxConcurrentRequests,
            Long version,
            LocalDateTime updatedAt
    ) {
        return new UserApiPolicyDecision(
                userId,
                group,
                group.getDisplayName(),
                enabled,
                maxConcurrentRequests,
                false,
                version,
                updatedAt
        );
    }
}
