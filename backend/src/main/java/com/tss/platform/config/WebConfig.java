package com.tss.platform.config;

import com.tss.platform.module1.interceptor.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String[] DEFAULT_ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*",
            "http://127.0.0.1:*"
    };

    // 改用Spring原生@Autowired，兼容性更强，无需额外依赖
    @Autowired
    private PermissionInterceptor permissionInterceptor;

    @Value("${app.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns = "http://localhost:*,http://127.0.0.1:*";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 添加CORS跨域支持
        registry.addMapping("/**")
                .allowedOriginPatterns(resolveAllowedOriginPatterns())
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有/api开头的接口，实现权限校验
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register/**",
                        "/api/user/sms/code",
                        "/api/user/forget/password",
                        "/api/v2/dataset-versions/*/lerobot/media"
                );
    }

    private String[] resolveAllowedOriginPatterns() {
        if (allowedOriginPatterns == null || allowedOriginPatterns.isBlank()) {
            return DEFAULT_ALLOWED_ORIGIN_PATTERNS;
        }
        String[] patterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isBlank())
                .filter(pattern -> !"*".equals(pattern))
                .toArray(String[]::new);
        return patterns.length == 0 ? DEFAULT_ALLOWED_ORIGIN_PATTERNS : patterns;
    }
}
