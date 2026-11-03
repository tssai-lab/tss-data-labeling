package com.tss.platform.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalInferenceCallbackAuthFilter extends OncePerRequestFilter {

    private final TrainingKubernetesProperties properties;

    public InternalInferenceCallbackAuthFilter(TrainingKubernetesProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean internalInferenceCallback = request.getRequestURI().startsWith("/api/internal/inference/");
        String token = request.getHeader("X-Internal-Token");
        boolean tokenValid = properties.matchesInternalCallbackToken(token);
        if (!internalInferenceCallback || !tokenValid) {
            filterChain.doFilter(request, response);
            return;
        }

        ServletRequestAttributes previousAttributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        StpUtil.login(0);
        StpUtil.getTokenSession().set("roleId", 1);
        StpUtil.getTokenSession().set("username", "internal-inference-callback");
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousAttributes == null) {
                RequestContextHolder.resetRequestAttributes();
            } else {
                RequestContextHolder.setRequestAttributes(previousAttributes);
            }
        }
    }
}
