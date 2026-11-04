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

/**
 * 仅为携带有效内部 token 的训练 Worker 回调建立临时登录上下文。
 * 训练回调令牌错误或缺失时直接拒绝，避免进入通用用户鉴权链。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalTrainingCallbackAuthFilter extends OncePerRequestFilter {

    private final TrainingKubernetesProperties properties;

    public InternalTrainingCallbackAuthFilter(TrainingKubernetesProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean internalTrainingCallback = request.getRequestURI().startsWith("/api/internal/training/");
        String token = request.getHeader("X-Internal-Token");
        boolean tokenValid = properties.matchesInternalCallbackToken(token);
        if (!internalTrainingCallback) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!tokenValid) {
            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid internal training callback token"
            );
            return;
        }

        ServletRequestAttributes previousAttributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        StpUtil.login(0);
        StpUtil.getTokenSession().set("roleId", 1);
        StpUtil.getTokenSession().set("username", "internal-training-callback");
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
