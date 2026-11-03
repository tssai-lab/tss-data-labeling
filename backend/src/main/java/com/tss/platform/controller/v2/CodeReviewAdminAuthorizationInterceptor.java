package com.tss.platform.controller.v2;

import com.tss.platform.security.AuthContext;
import com.tss.platform.service.CodeApprovalForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before controller argument binding, so malformed admin requests cannot
 * use a 400/404 difference to probe code resources.
 */
@Component
public class CodeReviewAdminAuthorizationInterceptor implements HandlerInterceptor {

    private final AuthContext authContext;

    public CodeReviewAdminAuthorizationInterceptor(AuthContext authContext) {
        this.authContext = authContext;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        boolean administrator;
        try {
            administrator = authContext.isAdmin();
        } catch (RuntimeException exception) {
            administrator = false;
        }
        if (!administrator) {
            throw new CodeApprovalForbiddenException();
        }
        return true;
    }
}
