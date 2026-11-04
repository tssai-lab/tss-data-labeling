package com.tss.platform.api;

import com.tss.platform.controller.v2.CodeReviewAdminAuthorizationInterceptor;
import com.tss.platform.controller.v2.V2AdminCodeVersionController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.V2CodeVersionQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2AdminCodeVersionControllerTest {

    private V2CodeVersionQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(V2CodeVersionQueryService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        mvc = MockMvcBuilders
                .standaloneSetup(new V2AdminCodeVersionController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .addInterceptors(new CodeReviewAdminAuthorizationInterceptor(authContext))
                .build();
    }

    @Test
    void listsForeignAssetVersionsThroughAdministratorEntryPoint() throws Exception {
        when(service.listForAssetAdmin("asset-1")).thenReturn(List.of(version()));

        mvc.perform(get("/api/v2/admin/code-assets/asset-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("version-1"))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].storagePath").doesNotExist());

        verify(service).listForAssetAdmin("asset-1");
    }

    @Test
    void lifecycleRoutesUseExplicitAdministratorMethods() throws Exception {
        when(service.deprecateAdmin("version-1")).thenReturn(version());
        when(service.archiveAdmin("version-1")).thenReturn(version());

        mvc.perform(post("/api/v2/admin/code-versions/version-1/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v2/admin/code-versions/version-1/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(service).deprecateAdmin("version-1");
        verify(service).archiveAdmin("version-1");
    }

    private static V2CodeVersionDto version() {
        return new V2CodeVersionDto(
                "version-1",
                "asset-1",
                "v1",
                "code.zip",
                12L,
                "READY",
                "a".repeat(64),
                "PASSED",
                "policy-v1",
                "APPROVED",
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null
        );
    }
}
