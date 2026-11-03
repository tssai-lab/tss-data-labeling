package com.tss.platform.module1.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.service.NativeDownloadTicketService;
import com.tss.platform.module1.security.UserApiConcurrencyLimiter;
import com.tss.platform.module1.security.UserApiFeatureClassifier;
import com.tss.platform.module1.security.UserApiPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionInterceptorDownloadTicketTest {

    @Test
    void browserGetTicketRestoresExistingLoginBeforeNormalPermissionChecks() throws Exception {
        NativeDownloadTicketService ticketService = mock(NativeDownloadTicketService.class);
        PermissionInterceptor interceptor = interceptor(ticketService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/files/download"
        );
        request.addParameter("objectName", "users/7/result.zip");
        request.addParameter(NativeDownloadTicketService.QUERY_PARAMETER, "opaque-ticket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ticketService.resolve("opaque-ticket", request)).thenReturn("existing-sa-token");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertTrue(interceptor.preHandle(request, response, new Object()));
            stp.verify(() -> StpUtil.setTokenValue("existing-sa-token"));
            stp.verify(StpUtil::checkLogin);
        }
        verify(ticketService).resolve("opaque-ticket", request);
    }

    @Test
    void expiredLoginBehindValidTicketIsStillRejectedByNormalLoginCheck() throws Exception {
        NativeDownloadTicketService ticketService = mock(NativeDownloadTicketService.class);
        PermissionInterceptor interceptor = interceptor(ticketService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/download");
        request.addParameter(NativeDownloadTicketService.QUERY_PARAMETER, "opaque-ticket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ticketService.resolve("opaque-ticket", request)).thenReturn("expired-sa-token");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::checkLogin).thenThrow(new IllegalStateException("expired"));
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus());
            stp.verify(() -> StpUtil.setTokenValue("expired-sa-token"));
            stp.verify(StpUtil::checkLogin);
        }
    }

    private static PermissionInterceptor interceptor(NativeDownloadTicketService ticketService) {
        return new PermissionInterceptor(
                ticketService,
                mock(UserApiPolicyService.class),
                new UserApiFeatureClassifier(),
                mock(UserApiConcurrencyLimiter.class)
        );
    }
}
