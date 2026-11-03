package com.tss.platform.api;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.controller.NativeDownloadTicketController;
import com.tss.platform.service.NativeDownloadTicketService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NativeDownloadTicketControllerTest {

    @Test
    void returnsOpaqueSameOriginDownloadUrlWithoutExposingSaToken() throws Exception {
        NativeDownloadTicketService service = mock(NativeDownloadTicketService.class);
        when(service.issue(eq("/api/files/download?objectName=users%2F1%2Ftrain.log"), eq("secret-token")))
                .thenReturn(new NativeDownloadTicketService.IssuedTicket(
                        "/api/files/download?objectName=users%2F1%2Ftrain.log&downloadTicket=opaque",
                        Instant.parse("2026-08-27T14:00:00Z")
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new NativeDownloadTicketController(service))
                .build();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("secret-token");
            mvc.perform(post("/api/download-tickets")
                            .contentType("application/json")
                            .content("{\"target\":\"/api/files/download?objectName=users%2F1%2Ftrain.log\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.downloadUrl").value(
                            "/api/files/download?objectName=users%2F1%2Ftrain.log&downloadTicket=opaque"
                    ))
                    .andExpect(jsonPath("$.data.downloadUrl").value(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-token"))
                    ));
        }
    }

    @Test
    void mapsInvalidTargetAndCapacityLimitToStableHttpStatuses() throws Exception {
        NativeDownloadTicketService service = mock(NativeDownloadTicketService.class);
        when(service.issue(eq("/api/not-a-download"), eq("token")))
                .thenThrow(new IllegalArgumentException("invalid"));
        when(service.issue(eq("/api/files/download"), eq("token")))
                .thenThrow(new IllegalStateException("full"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new NativeDownloadTicketController(service))
                .build();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token");
            mvc.perform(post("/api/download-tickets")
                            .contentType("application/json")
                            .content("{\"target\":\"/api/not-a-download\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/download-tickets")
                            .contentType("application/json")
                            .content("{\"target\":\"/api/files/download\"}"))
                    .andExpect(status().isTooManyRequests());
        }
    }
}
