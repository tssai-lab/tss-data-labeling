package com.tss.platform.module1.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.security.UserApiConcurrencyLimiter;
import com.tss.platform.module1.security.UserApiFeatureClassifier;
import com.tss.platform.module1.security.UserApiFeatureGroup;
import com.tss.platform.module1.security.UserApiPolicyDecision;
import com.tss.platform.module1.security.UserApiPolicyService;
import com.tss.platform.service.NativeDownloadTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger SYSTEM_LOG = LoggerFactory.getLogger("SYSTEM_LOG");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/user/register/username",
            "/api/user/register/mobile",
            "/api/user/sms/code",
            "/api/user/forget/password",
            "/api/user/login"
    );
    private static final List<String> USER_SELF_PATHS = Arrays.asList(
            "/api/user/current-user",
            "/api/user/logout"
    );

    private final NativeDownloadTicketService downloadTicketService;
    private final UserApiPolicyService userApiPolicyService;
    private final UserApiFeatureClassifier featureClassifier;
    private final UserApiConcurrencyLimiter concurrencyLimiter;

    private static final String ACQUIRED_LEASE_ATTRIBUTE =
            PermissionInterceptor.class.getName() + ".acquiredLease";

    public PermissionInterceptor(
            NativeDownloadTicketService downloadTicketService,
            UserApiPolicyService userApiPolicyService,
            UserApiFeatureClassifier featureClassifier,
            UserApiConcurrencyLimiter concurrencyLimiter
    ) {
        this.downloadTicketService = downloadTicketService;
        this.userApiPolicyService = userApiPolicyService;
        this.featureClassifier = featureClassifier;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestUri = request.getRequestURI();
        SYSTEM_LOG.debug("请求URI: {}", requestUri);

        if (isPublicPath(requestUri)) {
            return true;
        }

        try {
            String token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7).trim();
                if (!token.isEmpty()) {
                    StpUtil.setTokenValue(token);
                }
            } else if ("GET".equalsIgnoreCase(request.getMethod())) {
                String ticket = request.getParameter(NativeDownloadTicketService.QUERY_PARAMETER);
                if (ticket != null && !ticket.isBlank()) {
                    StpUtil.setTokenValue(downloadTicketService.resolve(ticket, request));
                }
            }
            StpUtil.checkLogin();
        } catch (Exception e) {
            SYSTEM_LOG.warn("Token验证失败: {}", e.getMessage());
            writeResult(response, HttpServletResponse.SC_UNAUTHORIZED, Result.unauthorized("请先登录"));
            return false;
        }

        if (isAdminOnlyPath(requestUri, request) && !isAdmin()) {
            SYSTEM_LOG.warn("权限验证失败，非管理员: uri={}", requestUri);
            writeResult(response, HttpServletResponse.SC_FORBIDDEN, Result.noAuth("无权限访问，仅管理员可操作"));
            return false;
        }

        java.util.Optional<UserApiFeatureGroup> featureGroup = featureClassifier.classify(requestUri);
        if (featureGroup.isEmpty()) {
            return true;
        }
        try {
            Integer userId = StpUtil.getLoginIdAsInt();
            UserApiPolicyDecision policy = userApiPolicyService.resolve(userId, featureGroup.get());
            if (!policy.enabled()) {
                SYSTEM_LOG.warn("用户API功能已禁用: userId={}, group={}, uri={}",
                        userId, featureGroup.get(), requestUri);
                writeResult(response, HttpServletResponse.SC_FORBIDDEN,
                        Result.noAuth("该功能已被管理员禁用"));
                return false;
            }
            Integer limit = policy.maxConcurrentRequests();
            if (limit != null) {
                java.util.Optional<UserApiConcurrencyLimiter.Lease> lease =
                        concurrencyLimiter.tryAcquire(userId, featureGroup.get(), limit);
                if (lease.isEmpty()) {
                    writeResult(response, 429,
                            Result.tooManyRequests("该功能当前并发请求数已达到上限，请稍后重试"));
                    return false;
                }
                request.setAttribute(ACQUIRED_LEASE_ATTRIBUTE, lease.get());
            }
        } catch (Exception exception) {
            SYSTEM_LOG.error("用户API策略检查失败: uri={}, error={}", requestUri, exception.getMessage(), exception);
            writeResult(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    Result.serviceUnavailable("权限策略服务暂时不可用，请稍后重试"));
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        Object lease = request.getAttribute(ACQUIRED_LEASE_ATTRIBUTE);
        if (lease instanceof UserApiConcurrencyLimiter.Lease acquiredLease) {
            try {
                concurrencyLimiter.release(acquiredLease);
            } catch (Exception releaseException) {
                SYSTEM_LOG.error("释放用户API并发计数失败: userId={}, group={}, error={}",
                        acquiredLease.userId(),
                        acquiredLease.featureGroup(),
                        releaseException.getMessage(),
                        releaseException);
            }
        }
    }

    private void writeResult(HttpServletResponse response, int httpStatus, Result<?> result) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try (PrintWriter writer = response.getWriter()) {
            writer.write(OBJECT_MAPPER.writeValueAsString(result));
            writer.flush();
        }
    }

    private boolean isPublicPath(String requestUri) {
        return PUBLIC_PATHS.stream().anyMatch(requestUri::startsWith);
    }

    private boolean isAdminOnlyPath(String requestUri, HttpServletRequest request) {
        // 日志查询/类型字典：登录用户均可访问，数据范围由 Controller 按 Token 裁剪（BUG-017/018）
        if (isLogQueryAllowedForAllRoles(requestUri, request)) {
            return false;
        }
        if (!requestUri.startsWith("/api/user") &&
            !requestUri.startsWith("/api/log") &&
            !requestUri.startsWith("/api/role") &&
            !requestUri.startsWith("/api/system/user") &&
            !requestUri.startsWith("/api/system/log") &&
            !requestUri.startsWith("/api/system/config")) {
            return false;
        }
        if (isPublicPath(requestUri)) {
            return false;
        }
        return USER_SELF_PATHS.stream().noneMatch(requestUri::startsWith);
    }

    private boolean isLogQueryAllowedForAllRoles(String requestUri, HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod()) && requestUri.startsWith("/api/log/query")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod()) && (
                requestUri.startsWith("/api/log/types")
                        || requestUri.startsWith("/api/system/log/types")
                        || requestUri.startsWith("/api/system/log/objects"))) {
            return true;
        }
        // 个人中心「我的操作记录」：GET /api/system/log/list + currentUsername=本人
        // 以及普通用户不带 currentUsername 时由 Controller 强制本人范围，故 list 对登录用户开放
        if ("GET".equalsIgnoreCase(request.getMethod()) && requestUri.startsWith("/api/system/log/list")) {
            return true;
        }
        return false;
    }

    private boolean isAdmin() {
        try {
            Integer roleId = (Integer) StpUtil.getTokenSession().get("roleId");
            return roleId != null && (roleId == 1 || roleId == 2);
        } catch (Exception e) {
            SYSTEM_LOG.error("isAdmin方法异常: {}", e.getMessage());
            return false;
        }
    }
}
