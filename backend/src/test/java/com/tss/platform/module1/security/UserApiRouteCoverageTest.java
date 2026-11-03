package com.tss.platform.module1.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserApiRouteCoverageTest {
    // 明确列出基础设施、登录自助和共享下载例外；新增业务接口不能靠未识别而绕过开关。
    private static final Set<String> EXACT_EXEMPTIONS = Set.of(
            "/api/login/account", "/api/login/outLogin", "/api/currentUser",
            "/api/user/login", "/api/user/current-user", "/api/user/logout");
    private static final List<String> EXEMPT_FAMILIES = List.of(
            "/health", "/api/internal/", "/api/files", "/api/download-tickets",
            "/api/user/register/", "/api/user/sms/code", "/api/user/forget/password");

    @Test
    void everyControllerRouteHasAFeatureGroupOrAnExplicitExemption() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var mappings = new Mappings();
        var classifier = new UserApiFeatureClassifier();
        List<String> missing = new ArrayList<>();
        int routes = 0;
        for (var bean : scanner.findCandidateComponents("com.tss.platform")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
                RequestMappingInfo mapping = mappings.mapping(method, controller);
                if (mapping == null) continue;
                for (String path : mapping.getPatternValues()) {
                    routes++;
                    if (classifier.classify(path).isEmpty() && !EXACT_EXEMPTIONS.contains(path)
                            && EXEMPT_FAMILIES.stream().noneMatch(path::startsWith)) {
                        missing.add(path + " -> " + controller.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }
        assertThat(routes).isGreaterThan(100);
        assertThat(missing).as("新增接口需登记功能组或有理由的明确例外").isEmpty();
    }

    static class Mappings extends RequestMappingHandlerMapping {
        RequestMappingInfo mapping(Method method, Class<?> type) { return getMappingForMethod(method, type); }
    }
}
