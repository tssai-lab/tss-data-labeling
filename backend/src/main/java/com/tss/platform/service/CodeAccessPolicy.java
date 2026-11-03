package com.tss.platform.service;

import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Keeps owner-only and explicit administrator access decisions separate.
 */
@Service
public class CodeAccessPolicy {

    private final AuthContext authContext;

    public CodeAccessPolicy(AuthContext authContext) {
        this.authContext = authContext;
    }

    public Integer currentUserId() {
        try {
            Integer currentUserId = authContext.currentUserId();
            if (currentUserId != null) {
                return currentUserId;
            }
        } catch (RuntimeException ignored) {
            // Hide authentication-provider details behind the resource boundary.
        }
        throw new CodeAssetAccessException();
    }

    public void require(CodeAccessScope scope, Integer ownerUserId) {
        if (scope == CodeAccessScope.ADMIN) {
            requireAdministrator();
            if (ownerUserId == null) {
                throw new CodeAssetAccessException();
            }
            return;
        }
        if (ownerUserId == null
                || !Objects.equals(ownerUserId, currentUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    public void requireAdministrator() {
        boolean administrator;
        try {
            administrator = authContext.isAdmin();
        } catch (RuntimeException exception) {
            administrator = false;
        }
        if (!administrator) {
            throw new CodeApprovalForbiddenException();
        }
    }
}
