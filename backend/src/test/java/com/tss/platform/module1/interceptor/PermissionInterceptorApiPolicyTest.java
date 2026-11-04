package com.tss.platform.module1.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.module1.security.UserApiConcurrencyLimiter;
import com.tss.platform.module1.security.UserApiFeatureClassifier;
import com.tss.platform.module1.security.UserApiFeatureGroup;
import com.tss.platform.module1.security.UserApiPolicyDecision;
import com.tss.platform.module1.security.UserApiPolicyService;
import com.tss.platform.service.NativeDownloadTicketService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionInterceptorApiPolicyTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "/api/task/create,TRAINING_TASK,3",
            "/api/task/list,TRAINING_TASK,3",
            "/api/task/stop,TRAINING_TASK,3",
            "/api/experiments/exp-1/versions,TRAINING_TASK,3",
            "/api/dataset/asset-201/summary,DATASET_ASSET,3",
            "/api/v2/admin/demo-assets/model/asset-1,SYSTEM_ADMIN_AUDIT,1"
    })
    void affectedRoutesHonorDisabledFeatures(String path, UserApiFeatureGroup group, int role) throws Exception {
        Fixture fixture = fixture();
        when(fixture.policyService.resolve(7, group))
                .thenReturn(UserApiPolicyDecision.explicit(7, group, false, null, 1L, null));
        var response = new MockHttpServletResponse();
        try (MockedStatic<StpUtil> stp = loggedInUser(7, role)) {
            assertThat(fixture.interceptor.preHandle(new MockHttpServletRequest("POST", path), response, new Object())).isFalse();
        }
        assertThat(response.getStatus()).isEqualTo(403);
        verify(fixture.policyService).resolve(7, group);
    }

    @Test
    void disabledFeatureIsRejectedByBackendWithHttp403() throws Exception {
        Fixture fixture = fixture();
        when(fixture.policyService.resolve(7, UserApiFeatureGroup.TRAINING_TASK))
                .thenReturn(UserApiPolicyDecision.explicit(7, UserApiFeatureGroup.TRAINING_TASK, false, null, 3L, null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/experiments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = loggedInUser(7, 3)) {
            assertThat(fixture.interceptor.preHandle(request, response, new Object())).isFalse();
        }

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("该功能已被管理员禁用");
        verify(fixture.limiter, never()).tryAcquire(7, UserApiFeatureGroup.TRAINING_TASK, 1);
    }

    @Test
    void concurrentLimitUses429AndSuccessfulRequestReleasesSlot() throws Exception {
        Fixture fixture = fixture();
        when(fixture.policyService.resolve(7, UserApiFeatureGroup.MODEL_ASSET))
                .thenReturn(UserApiPolicyDecision.explicit(7, UserApiFeatureGroup.MODEL_ASSET, true, 1, 1L, null));
        UserApiConcurrencyLimiter.Lease lease = new UserApiConcurrencyLimiter.Lease(
                7, UserApiFeatureGroup.MODEL_ASSET, "lease-1");
        when(fixture.limiter.tryAcquire(7, UserApiFeatureGroup.MODEL_ASSET, 1))
                .thenReturn(Optional.of(lease));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/model-assets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = loggedInUser(7, 3)) {
            assertThat(fixture.interceptor.preHandle(request, response, new Object())).isTrue();
            fixture.interceptor.afterCompletion(request, response, new Object(), null);
        }

        verify(fixture.limiter).release(lease);

        when(fixture.limiter.tryAcquire(7, UserApiFeatureGroup.MODEL_ASSET, 1))
                .thenReturn(Optional.empty());
        MockHttpServletRequest blockedRequest = new MockHttpServletRequest("GET", "/api/model-assets");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        try (MockedStatic<StpUtil> stp = loggedInUser(7, 3)) {
            assertThat(fixture.interceptor.preHandle(blockedRequest, blockedResponse, new Object())).isFalse();
        }
        assertThat(blockedResponse.getStatus()).isEqualTo(429);
        verify(fixture.limiter, times(2)).tryAcquire(7, UserApiFeatureGroup.MODEL_ASSET, 1);
    }

    @Test
    void userPolicyCannotElevateAUserPastTheExistingRoleBoundary() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/system/config/update");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = loggedInUser(7, 3)) {
            assertThat(fixture.interceptor.preHandle(request, response, new Object())).isFalse();
        }

        assertThat(response.getStatus()).isEqualTo(403);
        verify(fixture.policyService, never()).resolve(7, UserApiFeatureGroup.SYSTEM_ADMIN_AUDIT);
    }

    @Test
    void policyStorageFailureIsClosedWith503InsteadOfSilentlyAllowingTheRequest() throws Exception {
        Fixture fixture = fixture();
        when(fixture.policyService.resolve(7, UserApiFeatureGroup.MODEL_ASSET))
                .thenThrow(new IllegalStateException("database unavailable"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/model-assets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = loggedInUser(7, 3)) {
            assertThat(fixture.interceptor.preHandle(request, response, new Object())).isFalse();
        }

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("权限策略服务暂时不可用");
        verify(fixture.limiter, never()).tryAcquire(7, UserApiFeatureGroup.MODEL_ASSET, 1);
    }

    private static Fixture fixture() {
        NativeDownloadTicketService ticketService = mock(NativeDownloadTicketService.class);
        UserApiPolicyService policyService = mock(UserApiPolicyService.class);
        UserApiConcurrencyLimiter limiter = mock(UserApiConcurrencyLimiter.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(
                ticketService,
                policyService,
                new UserApiFeatureClassifier(),
                limiter
        );
        return new Fixture(interceptor, policyService, limiter);
    }

    private static MockedStatic<StpUtil> loggedInUser(int userId, int roleId) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::getLoginIdAsInt).thenReturn(userId);
        stp.when(StpUtil::getTokenSession).thenReturn(mock(cn.dev33.satoken.session.SaSession.class));
        when(StpUtil.getTokenSession().get("roleId")).thenReturn(roleId);
        return stp;
    }

    private record Fixture(
            PermissionInterceptor interceptor,
            UserApiPolicyService policyService,
            UserApiConcurrencyLimiter limiter
    ) {
    }
}
