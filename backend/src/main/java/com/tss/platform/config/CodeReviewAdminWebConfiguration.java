package com.tss.platform.config;

import com.tss.platform.controller.v2.CodeReviewAdminAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Module-two authorization gate for every explicit administrator namespace. */
@Configuration
public class CodeReviewAdminWebConfiguration implements WebMvcConfigurer {

    private final CodeReviewAdminAuthorizationInterceptor authorizationInterceptor;

    public CodeReviewAdminWebConfiguration(
            CodeReviewAdminAuthorizationInterceptor authorizationInterceptor
    ) {
        this.authorizationInterceptor = authorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/api/v2/admin/**")
                // The existing login interceptor uses the default order (0).
                .order(10);
    }
}
